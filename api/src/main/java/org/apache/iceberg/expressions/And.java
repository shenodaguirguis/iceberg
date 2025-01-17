/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.expressions;

import org.apache.iceberg.StructLike;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;

public class And implements Expression, Bound<Boolean> {
  private final Expression left;
  private final Expression right;

  And(Expression left, Expression right) {
    this.left = left;
    this.right = right;
  }

  public Expression left() {
    return left;
  }

  public Expression right() {
    return right;
  }

  @Override
  public Operation op() {
    return Expression.Operation.AND;
  }

  @Override
  public Expression negate() {
    // not(and(a, b)) => or(not(a), not(b))
    return Expressions.or(left.negate(), right.negate());
  }

  @Override
  public BoundReference<?> ref() {
    return null;
  }

  @Override
  public Boolean eval(StructLike struct) {
    Preconditions.checkNotNull(left, "Left expression cannot be null.");
    Preconditions.checkNotNull(right, "Right expression cannot be null.");
    if (!(left instanceof Bound) || !(right instanceof Bound)) {
      throw new IllegalStateException("Unbound predicate not expected");
    }
    return ((Bound<Boolean>) left).eval(struct) && ((Bound<Boolean>) right).eval(struct);
  }

  @Override
  public String toString() {
    return String.format("(%s and %s)", left, right);
  }
}
