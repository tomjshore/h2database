/*
 * Copyright 2004-2020 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.expression.condition;

import java.util.ArrayList;
import org.h2.api.ErrorCode;
import org.h2.engine.Session;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionColumn;
import org.h2.expression.ExpressionVisitor;
import org.h2.expression.Parameter;
import org.h2.expression.TypedValueExpression;
import org.h2.expression.ValueExpression;
import org.h2.expression.aggregate.Aggregate;
import org.h2.expression.aggregate.AggregateType;
import org.h2.index.IndexCondition;
import org.h2.message.DbException;
import org.h2.table.Column;
import org.h2.table.ColumnResolver;
import org.h2.table.TableFilter;
import org.h2.value.TypeInfo;
import org.h2.value.Value;
import org.h2.value.ValueBoolean;
import org.h2.value.ValueNull;

/**
 * Example comparison expressions are ID=1, NAME=NAME, NAME IS NULL.
 *
 * @author Thomas Mueller
 * @author Noel Grandin
 * @author Nicolas Fortin, Atelier SIG, IRSTV FR CNRS 24888
 */
public final class Comparison extends Condition {

    /**
     * This is a flag meaning the comparison is null safe (meaning never returns
     * NULL even if one operand is NULL). Only EQUAL and NOT_EQUAL are supported
     * currently.
     */
    public static final int NULL_SAFE = 16;

    /**
     * The comparison type meaning = as in ID=1.
     */
    public static final int EQUAL = 0;

    /**
     * The comparison type meaning ID IS 1 (ID IS NOT DISTINCT FROM 1).
     */
    public static final int EQUAL_NULL_SAFE = EQUAL | NULL_SAFE;

    /**
     * The comparison type meaning &gt;= as in ID&gt;=1.
     */
    public static final int BIGGER_EQUAL = 1;

    /**
     * The comparison type meaning &gt; as in ID&gt;1.
     */
    public static final int BIGGER = 2;

    /**
     * The comparison type meaning &lt;= as in ID&lt;=1.
     */
    public static final int SMALLER_EQUAL = 3;

    /**
     * The comparison type meaning &lt; as in ID&lt;1.
     */
    public static final int SMALLER = 4;

    /**
     * The comparison type meaning &lt;&gt; as in ID&lt;&gt;1.
     */
    public static final int NOT_EQUAL = 5;

    /**
     * The comparison type meaning ID IS NOT 1 (ID IS DISTINCT FROM 1).
     */
    public static final int NOT_EQUAL_NULL_SAFE = NOT_EQUAL | NULL_SAFE;

    /**
     * This is a pseudo comparison type that is only used for index conditions.
     * It means the comparison will always yield FALSE. Example: 1=0.
     */
    public static final int FALSE = 6;

    /**
     * This is a pseudo comparison type that is only used for index conditions.
     * It means equals any value of a list. Example: IN(1, 2, 3).
     */
    public static final int IN_LIST = 7;

    /**
     * This is a pseudo comparison type that is only used for index conditions.
     * It means equals any value of a list. Example: IN(SELECT ...).
     */
    public static final int IN_QUERY = 8;

    /**
     * This is a comparison type that is only used for spatial index
     * conditions (operator "&amp;&amp;").
     */
    public static final int SPATIAL_INTERSECTS = 9;

    private int compareType;
    private Expression left;
    private Expression right;
    private final boolean whenOperand;

    public Comparison(int compareType, Expression left, Expression right, boolean whenOperand) {
        this.left = left;
        this.right = right;
        this.compareType = compareType;
        this.whenOperand = whenOperand;
    }

    @Override
    public StringBuilder getSQL(StringBuilder builder, int sqlFlags) {
        return getWhenSQL(left.getSQL(builder.append('('), sqlFlags), sqlFlags).append(')');
    }

    @Override
    public StringBuilder getWhenSQL(StringBuilder builder, int sqlFlags) {
        boolean encloseRight = false;
        switch (compareType) {
        case SPATIAL_INTERSECTS:
        case EQUAL:
        case BIGGER_EQUAL:
        case BIGGER:
        case SMALLER_EQUAL:
        case SMALLER:
        case NOT_EQUAL:
            if (right instanceof Aggregate && ((Aggregate) right).getAggregateType() == AggregateType.ANY) {
                encloseRight = true;
            }
        }
        builder.append(' ').append(getCompareOperator(compareType)).append(' ');
        if (encloseRight) {
            builder.append('(');
        }
        right.getSQL(builder, sqlFlags);
        if (encloseRight) {
            builder.append(')');
        }
        return builder;
    }

    /**
     * Get the comparison operator string ("=", ">",...).
     *
     * @param compareType the compare type
     * @return the string
     */
    static String getCompareOperator(int compareType) {
        switch (compareType) {
        case EQUAL:
            return "=";
        case EQUAL_NULL_SAFE:
            return "IS NOT DISTINCT FROM";
        case BIGGER_EQUAL:
            return ">=";
        case BIGGER:
            return ">";
        case SMALLER_EQUAL:
            return "<=";
        case SMALLER:
            return "<";
        case NOT_EQUAL:
            return "<>";
        case NOT_EQUAL_NULL_SAFE:
            return "IS DISTINCT FROM";
        case SPATIAL_INTERSECTS:
            return "&&";
        default:
            throw DbException.throwInternalError("compareType=" + compareType);
        }
    }

    @Override
    public Expression optimize(Session session) {
        left = left.optimize(session);
        right = right.optimize(session);
        // TODO check row values too
        if (right.getType().getValueType() == Value.ARRAY && left.getType().getValueType() != Value.ARRAY) {
            throw DbException.get(ErrorCode.COMPARING_ARRAY_TO_SCALAR);
        }
        if (whenOperand) {
            return this;
        }
        if (right instanceof ExpressionColumn) {
            if (left.isConstant() || left instanceof Parameter) {
                Expression temp = left;
                left = right;
                right = temp;
                compareType = getReversedCompareType(compareType);
            }
        }
        if (left instanceof ExpressionColumn) {
            if (right.isConstant()) {
                Value r = right.getValue(session);
                if (r == ValueNull.INSTANCE) {
                    if ((compareType & NULL_SAFE) == 0) {
                        return TypedValueExpression.UNKNOWN;
                    }
                }
                TypeInfo colType = left.getType(), constType = r.getType();
                int constValueType = constType.getValueType();
                if (constValueType != colType.getValueType()) {
                    TypeInfo resType = TypeInfo.getHigherType(colType, constType);
                    // If not, the column values will need to be promoted
                    // to constant type, but vise versa, then let's do this here
                    // once.
                    if (constValueType != resType.getValueType()) {
                        Column column = ((ExpressionColumn) left).getColumn();
                        right = ValueExpression.get(r.convertTo(resType, session, column));
                    }
                }
            } else if (right instanceof Parameter) {
                ((Parameter) right).setColumn(
                        ((ExpressionColumn) left).getColumn());
            }
        }
        if (left.isConstant() && right.isConstant()) {
            return ValueExpression.getBoolean(getValue(session));
        }
        if (left.isNullConstant() || right.isNullConstant()) {
            // TODO NULL handling: maybe issue a warning when comparing with
            // a NULL constants
            if ((compareType & NULL_SAFE) == 0) {
                return TypedValueExpression.UNKNOWN;
            }
            if (compareType == EQUAL_NULL_SAFE || compareType == NOT_EQUAL_NULL_SAFE) {
                Expression e = left.isNullConstant() ? right : left;
                int type = e.getType().getValueType();
                if (type != Value.UNKNOWN && type != Value.ROW) {
                    return new NullPredicate(e, compareType == NOT_EQUAL_NULL_SAFE, false);
                }
            }
        }
        return this;
    }

    @Override
    public Value getValue(Session session) {
        Value l = left.getValue(session);
        // Optimization: do not evaluate right if not necessary
        if (l == ValueNull.INSTANCE && (compareType & NULL_SAFE) == 0) {
            return ValueNull.INSTANCE;
        }
        return compare(session, l, right.getValue(session), compareType);
    }

    @Override
    public boolean getWhenValue(Session session, Value left) {
        if (!whenOperand) {
            return super.getWhenValue(session, left);
        }
        // Optimization: do not evaluate right if not necessary
        if (left == ValueNull.INSTANCE && (compareType & NULL_SAFE) == 0) {
            return false;
        }
        return compare(session, left, right.getValue(session), compareType).getBoolean();
    }

    /**
     * Compare two values.
     *
     * @param session the session
     * @param l the first value
     * @param r the second value
     * @param compareType the compare type
     * @return result of comparison, either TRUE, FALSE, or NULL
     */
    static Value compare(Session session, Value l, Value r, int compareType) {
        Value result;
        switch (compareType) {
        case EQUAL: {
            int cmp = session.compareWithNull(l, r, true);
            if (cmp == 0) {
                result = ValueBoolean.TRUE;
            } else if (cmp == Integer.MIN_VALUE) {
                result = ValueNull.INSTANCE;
            } else {
                result = ValueBoolean.FALSE;
            }
            break;
        }
        case EQUAL_NULL_SAFE:
            result = ValueBoolean.get(session.areEqual(l, r));
            break;
        case NOT_EQUAL: {
            int cmp = session.compareWithNull(l, r, true);
            if (cmp == 0) {
                result = ValueBoolean.FALSE;
            } else if (cmp == Integer.MIN_VALUE) {
                result = ValueNull.INSTANCE;
            } else {
                result = ValueBoolean.TRUE;
            }
            break;
        }
        case NOT_EQUAL_NULL_SAFE:
            result = ValueBoolean.get(!session.areEqual(l, r));
            break;
        case BIGGER_EQUAL: {
            int cmp = session.compareWithNull(l, r, false);
            if (cmp >= 0) {
                result = ValueBoolean.TRUE;
            } else if (cmp == Integer.MIN_VALUE) {
                result = ValueNull.INSTANCE;
            } else {
                result = ValueBoolean.FALSE;
            }
            break;
        }
        case BIGGER: {
            int cmp = session.compareWithNull(l, r, false);
            if (cmp > 0) {
                result = ValueBoolean.TRUE;
            } else if (cmp == Integer.MIN_VALUE) {
                result = ValueNull.INSTANCE;
            } else {
                result = ValueBoolean.FALSE;
            }
            break;
        }
        case SMALLER_EQUAL: {
            int cmp = session.compareWithNull(l, r, false);
            if (cmp == Integer.MIN_VALUE) {
                result = ValueNull.INSTANCE;
            } else {
                result = ValueBoolean.get(cmp <= 0);
            }
            break;
        }
        case SMALLER: {
            int cmp = session.compareWithNull(l, r, false);
            if (cmp == Integer.MIN_VALUE) {
                result = ValueNull.INSTANCE;
            } else {
                result = ValueBoolean.get(cmp < 0);
            }
            break;
        }
        case SPATIAL_INTERSECTS: {
            if (l == ValueNull.INSTANCE || r == ValueNull.INSTANCE) {
                result = ValueNull.INSTANCE;
            } else {
                result = ValueBoolean.get(l.convertToGeometry(null).intersectsBoundingBox(r.convertToGeometry(null)));
            }
            break;
        }
        default:
            throw DbException.throwInternalError("type=" + compareType);
        }
        return result;
    }

    private static int getReversedCompareType(int type) {
        switch (type) {
        case EQUAL:
        case EQUAL_NULL_SAFE:
        case NOT_EQUAL:
        case NOT_EQUAL_NULL_SAFE:
        case SPATIAL_INTERSECTS:
            return type;
        case BIGGER_EQUAL:
            return SMALLER_EQUAL;
        case BIGGER:
            return SMALLER;
        case SMALLER_EQUAL:
            return BIGGER_EQUAL;
        case SMALLER:
            return BIGGER;
        default:
            throw DbException.throwInternalError("type=" + type);
        }
    }

    @Override
    public Expression getNotIfPossible(Session session) {
        if (compareType == SPATIAL_INTERSECTS || whenOperand) {
            return null;
        }
        int type = getNotCompareType();
        return new Comparison(type, left, right, false);
    }

    private int getNotCompareType() {
        switch (compareType) {
        case EQUAL:
            return NOT_EQUAL;
        case EQUAL_NULL_SAFE:
            return NOT_EQUAL_NULL_SAFE;
        case NOT_EQUAL:
            return EQUAL;
        case NOT_EQUAL_NULL_SAFE:
            return EQUAL_NULL_SAFE;
        case BIGGER_EQUAL:
            return SMALLER;
        case BIGGER:
            return SMALLER_EQUAL;
        case SMALLER_EQUAL:
            return BIGGER;
        case SMALLER:
            return BIGGER_EQUAL;
        default:
            throw DbException.throwInternalError("type=" + compareType);
        }
    }

    @Override
    public void createIndexConditions(Session session, TableFilter filter) {
        if (!whenOperand) {
            createIndexConditions(filter, left, right, compareType);
        }
    }

    static void createIndexConditions(TableFilter filter, Expression left, Expression right, int compareType) {
        if (!filter.getTable().isQueryComparable()) {
            return;
        }
        ExpressionColumn l = null;
        if (left instanceof ExpressionColumn) {
            l = (ExpressionColumn) left;
            if (filter != l.getTableFilter()) {
                l = null;
            }
        }
        ExpressionColumn r = null;
        if (right instanceof ExpressionColumn) {
            r = (ExpressionColumn) right;
            if (filter != r.getTableFilter()) {
                r = null;
            }
        }
        // one side must be from the current filter
        if (l == null && r == null) {
            return;
        }
        if (l != null && r != null) {
            return;
        }
        if (l == null) {
            ExpressionVisitor visitor =
                    ExpressionVisitor.getNotFromResolverVisitor(filter);
            if (!left.isEverything(visitor)) {
                return;
            }
        } else if (r == null) {
            ExpressionVisitor visitor =
                    ExpressionVisitor.getNotFromResolverVisitor(filter);
            if (!right.isEverything(visitor)) {
                return;
            }
        } else {
            // if both sides are part of the same filter, it can't be used for
            // index lookup
            return;
        }
        boolean addIndex;
        switch (compareType) {
        case NOT_EQUAL:
        case NOT_EQUAL_NULL_SAFE:
            addIndex = false;
            break;
        case EQUAL:
        case EQUAL_NULL_SAFE:
        case BIGGER:
        case BIGGER_EQUAL:
        case SMALLER_EQUAL:
        case SMALLER:
        case SPATIAL_INTERSECTS:
            addIndex = true;
            break;
        default:
            throw DbException.throwInternalError("type=" + compareType);
        }
        if (addIndex) {
            if (l != null) {
                int rType = right.getType().getValueType();
                if (l.getType().getValueType() == rType || rType != Value.VARCHAR_IGNORECASE) {
                    filter.addIndexCondition(
                            IndexCondition.get(compareType, l, right));
                }
            } else if (r != null) {
                int lType = left.getType().getValueType();
                if (r.getType().getValueType() == lType || lType != Value.VARCHAR_IGNORECASE) {
                    int compareRev = getReversedCompareType(compareType);
                    filter.addIndexCondition(
                            IndexCondition.get(compareRev, r, left));
                }
            }
        }
    }

    @Override
    public void setEvaluatable(TableFilter tableFilter, boolean b) {
        left.setEvaluatable(tableFilter, b);
        if (right != null) {
            right.setEvaluatable(tableFilter, b);
        }
    }

    @Override
    public void updateAggregate(Session session, int stage) {
        left.updateAggregate(session, stage);
        if (right != null) {
            right.updateAggregate(session, stage);
        }
    }

    @Override
    public void mapColumns(ColumnResolver resolver, int level, int state) {
        left.mapColumns(resolver, level, state);
        right.mapColumns(resolver, level, state);
    }

    @Override
    public boolean isEverything(ExpressionVisitor visitor) {
        return left.isEverything(visitor) && right.isEverything(visitor);
    }

    @Override
    public int getCost() {
        return left.getCost() + right.getCost() + 1;
    }

    /**
     * Get the other expression if this is an equals comparison and the other
     * expression matches.
     *
     * @param match the expression that should match
     * @return null if no match, the other expression if there is a match
     */
    Expression getIfEquals(Expression match) {
        if (compareType == EQUAL) {
            String sql = match.getSQL(DEFAULT_SQL_FLAGS);
            if (left.getSQL(DEFAULT_SQL_FLAGS).equals(sql)) {
                return right;
            } else if (right.getSQL(DEFAULT_SQL_FLAGS).equals(sql)) {
                return left;
            }
        }
        return null;
    }

    /**
     * Get an additional condition if possible. Example: given two conditions
     * A=B AND B=C, the new condition A=C is returned.
     *
     * @param session the session
     * @param other the second condition
     * @return null or the third condition for indexes
     */
    Expression getAdditionalAnd(Session session, Comparison other) {
        if (compareType == EQUAL && other.compareType == EQUAL && !whenOperand) {
            boolean lc = left.isConstant();
            boolean rc = right.isConstant();
            boolean l2c = other.left.isConstant();
            boolean r2c = other.right.isConstant();
            String l = left.getSQL(DEFAULT_SQL_FLAGS);
            String l2 = other.left.getSQL(DEFAULT_SQL_FLAGS);
            String r = right.getSQL(DEFAULT_SQL_FLAGS);
            String r2 = other.right.getSQL(DEFAULT_SQL_FLAGS);
            // a=b AND a=c
            // must not compare constants. example: NOT(B=2 AND B=3)
            if (!(rc && r2c) && l.equals(l2)) {
                return new Comparison(EQUAL, right, other.right, false);
            } else if (!(rc && l2c) && l.equals(r2)) {
                return new Comparison(EQUAL, right, other.left, false);
            } else if (!(lc && r2c) && r.equals(l2)) {
                return new Comparison(EQUAL, left, other.right, false);
            } else if (!(lc && l2c) && r.equals(r2)) {
                return new Comparison(EQUAL, left, other.left, false);
            }
        }
        return null;
    }

    /**
     * Replace the OR condition with IN condition if possible. Example: given
     * the two conditions A=1 OR A=2, the new condition A IN(1, 2) is returned.
     *
     * @param session the session
     * @param other the second condition
     * @return null or the joined IN condition
     */
    Expression optimizeOr(Session session, Comparison other) {
        if (compareType == EQUAL && other.compareType == EQUAL) {
            boolean lc = left.isConstant();
            boolean rc = right.isConstant();
            boolean l2c = other.left.isConstant();
            boolean r2c = other.right.isConstant();
            String l = left.getSQL(DEFAULT_SQL_FLAGS);
            String l2 = other.left.getSQL(DEFAULT_SQL_FLAGS);
            String r = right.getSQL(DEFAULT_SQL_FLAGS);
            String r2 = other.right.getSQL(DEFAULT_SQL_FLAGS);
            // a=b OR a=c
            if (rc && r2c && l.equals(l2)) {
                return getConditionIn(session, left, right, other.right);
            } else if (rc && l2c && l.equals(r2)) {
                return getConditionIn(session, left, right, other.left);
            } else if (lc && r2c && r.equals(l2)) {
                return getConditionIn(session, right, left, other.right);
            } else if (lc && l2c && r.equals(r2)) {
                return getConditionIn(session, right, left, other.left);
            }
        }
        return null;
    }

    private static ConditionIn getConditionIn(Session session, Expression left, Expression value1, Expression value2) {
        ArrayList<Expression> right = new ArrayList<>(2);
        right.add(value1);
        right.add(value2);
        return new ConditionIn(left, false, false, right);
    }

    @Override
    public int getSubexpressionCount() {
        return 2;
    }

    @Override
    public Expression getSubexpression(int index) {
        switch (index) {
        case 0:
            return left;
        case 1:
            return right;
        default:
            throw new IndexOutOfBoundsException();
        }
    }

}
