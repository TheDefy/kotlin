/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.js.inline.clean

import org.jetbrains.kotlin.js.backend.ast.*
import org.jetbrains.kotlin.utils.addIfNotNull

object LabeledBlockToDoWhileTransformation {
    fun apply(fragments: List<JsProgramFragment>) {
        for (fragment in fragments) {
            apply(fragment.declarationBlock)
            apply(fragment.initializerBlock)
        }
    }

    fun apply(root: JsNode) {
        object : JsVisitorWithContextImpl() {
            val loopStack = mutableListOf<JsStatement>()
            val newFakeLoops = HashSet<JsDoWhile>()

            // If labeled block sits in between loop and corresponding unlabeled breaks/continues
            // we have to label this loop, breaks and continues in order to preserve their
            // relationships across new fake do-while loop
            val loopsToLabel = HashSet<JsStatement>()

            override fun endVisit(x: JsLabel, ctx: JsContext<JsNode>) {
                if (x.statement is JsBlock) {
                    loopsToLabel.addIfNotNull(loopStack.lastOrNull())
                    val fakeLoop = JsDoWhile(JsBooleanLiteral(false), x.statement)
                    newFakeLoops.add(fakeLoop)
                    x.statement = fakeLoop
                }
                super.endVisit(x, ctx)
            }

            override fun visit(x: JsWhile, ctx: JsContext<JsNode>) = visitLoop(x)
            override fun visit(x: JsDoWhile, ctx: JsContext<JsNode>) = visitLoop(x)
            override fun visit(x: JsFor, ctx: JsContext<JsNode>) = visitLoop(x)
            override fun visit(x: JsForIn, ctx: JsContext<JsNode>) = visitLoop(x)

            override fun endVisit(x: JsWhile, ctx: JsContext<JsNode>) = endVisitLoop(x, ctx)
            override fun endVisit(x: JsDoWhile, ctx: JsContext<JsNode>) = endVisitLoop(x, ctx)
            override fun endVisit(x: JsFor, ctx: JsContext<JsNode>) = endVisitLoop(x, ctx)
            override fun endVisit(x: JsForIn, ctx: JsContext<JsNode>) = endVisitLoop(x, ctx)

            private fun visitLoop(x: JsStatement): Boolean {
                loopStack.add(x)
                return true
            }

            private fun endVisitLoop(x: JsStatement, ctx: JsContext<JsNode>) {
                loopStack.removeAt(loopStack.lastIndex)
                if (loopsToLabel.contains(x)) {
                    val label = JsScope.declareTemporaryName("loop_label")
                    labelLoopBreaksAndContinues(x, newFakeLoops, label.makeRef())
                    ctx.replaceMe(JsLabel(label, x))
                }
            }
        }.accept(root)
    }

    /*
    Label unlabeled breaks that correspond to current loop.
    Skip newly created fake do-while loops.
     */
    private fun labelLoopBreaksAndContinues(loop: JsStatement, fakeLoops: Set<JsDoWhile>, label: JsNameRef) {
        object : JsVisitorWithContextImpl() {
            override fun visit(x: JsWhile, ctx: JsContext<JsNode>): Boolean = visitLoop(x)
            override fun visit(x: JsDoWhile, ctx: JsContext<JsNode>): Boolean = visitLoop(x)
            override fun visit(x: JsFor, ctx: JsContext<JsNode>): Boolean = visitLoop(x)
            override fun visit(x: JsForIn, ctx: JsContext<JsNode>): Boolean = visitLoop(x)
            private fun visitLoop(x: JsStatement): Boolean = fakeLoops.contains(x) || x === loop

            override fun endVisit(x: JsBreak, ctx: JsContext<JsNode>) {
                if (x.label == null)
                    ctx.replaceMe(JsBreak(label))
                super.endVisit(x, ctx)
            }

            override fun endVisit(x: JsContinue, ctx: JsContext<JsNode>) {
                if (x.label == null)
                    ctx.replaceMe(JsContinue(label))
                super.endVisit(x, ctx)
            }
        }.accept(loop)
    }
}
