/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.rel.rules;

import org.apache.calcite.plan.Contexts;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.logical.LogicalCorrelate;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelBuilderFactory;
import org.apache.calcite.util.ImmutableBitSet;

import org.immutables.value.Value;

/**
 * Rule that converts a {@link org.apache.calcite.rel.core.Join}
 * into a {@link org.apache.calcite.rel.logical.LogicalCorrelate}, which can
 * then be implemented using nested loops.
 *
 * <p>For example,</p>
 *
 * <blockquote><code>select * from emp join dept on emp.deptno =
 * dept.deptno</code></blockquote>
 *
 * <p>becomes a Correlator which restarts LogicalTableScan("DEPT") for each
 * row read from LogicalTableScan("EMP").</p>
 *
 * <p>This rule is not applicable if for certain types of outer join. For
 * example,</p>
 *
 * <blockquote><code>select * from emp right join dept on emp.deptno =
 * dept.deptno</code></blockquote>
 *
 * <p>would require emitting a NULL emp row if a certain department contained no
 * employees, and Correlator cannot do that.
 *
 * @see CoreRules#JOIN_TO_CORRELATE
 */
@Value.Enclosing
public class JoinToCorrelateRule
    extends RelRule<JoinToCorrelateRule.Config>
    implements TransformationRule {

  /** Creates a JoinToCorrelateRule. */
  protected JoinToCorrelateRule(Config config) {
    super(config);
  }

  @Deprecated // to be removed before 2.0
  public JoinToCorrelateRule(RelBuilderFactory relBuilderFactory) {
    this(Config.DEFAULT.withRelBuilderFactory(relBuilderFactory)
        .as(Config.class)
        .withOperandFor(LogicalJoin.class));
  }

  @Deprecated // to be removed before 2.0
  protected JoinToCorrelateRule(RelFactories.FilterFactory filterFactory) {
    this(Config.DEFAULT
        .withRelBuilderFactory(RelBuilder.proto(Contexts.of(filterFactory)))
        .as(Config.class)
        .withOperandFor(LogicalJoin.class));
  }

  //~ Methods ----------------------------------------------------------------

  @Override public boolean matches(RelOptRuleCall call) {
    Join join = call.rel(0);
    return !join.getJoinType().generatesNullsOnLeft();
  }

  @Override public void onMatch(RelOptRuleCall call) {
    assert matches(call);
    final Join join = call.rel(0);
    RelNode right = join.getRight();
    final RelNode left = join.getLeft();
    final int leftFieldCount = left.getRowType().getFieldCount();
    final RelOptCluster cluster = join.getCluster();
    final RexBuilder rexBuilder = cluster.getRexBuilder();
    final RelBuilder relBuilder = call.builder();
    final CorrelationId correlationId = cluster.createCorrel();
    final RexNode corrVar =
        rexBuilder.makeCorrel(left.getRowType(), correlationId);
    final ImmutableBitSet.Builder requiredColumns = ImmutableBitSet.builder();

    // Replace all references of left input with FieldAccess(corrVar, field)
    final RexNode joinCondition = join.getCondition().accept(new RexShuttle() {
      @Override public RexNode visitInputRef(RexInputRef input) {
        int field = input.getIndex();
        if (field >= leftFieldCount) {
          return rexBuilder.makeInputRef(input.getType(),
              input.getIndex() - leftFieldCount);
        }
        requiredColumns.set(field);
        return rexBuilder.makeFieldAccess(corrVar, field);
      }
    });

    relBuilder.push(right).filter(joinCondition);

    RelNode newRel =
        LogicalCorrelate.create(left,
            relBuilder.build(),
            correlationId,
            requiredColumns.build(),
            join.getJoinType());
    call.transformTo(newRel);
  }

  /** Rule configuration. */
  @Value.Immutable
  public interface Config extends RelRule.Config {
    Config DEFAULT = ImmutableJoinToCorrelateRule.Config.of()
        .withOperandFor(LogicalJoin.class);

    @Override default JoinToCorrelateRule toRule() {
      return new JoinToCorrelateRule(this);
    }

    /** Defines an operand tree for the given classes. */
    default Config withOperandFor(Class<? extends Join> joinClass) {
      return withOperandSupplier(b -> b.operand(joinClass).anyInputs())
          .as(Config.class);
    }
  }
}
