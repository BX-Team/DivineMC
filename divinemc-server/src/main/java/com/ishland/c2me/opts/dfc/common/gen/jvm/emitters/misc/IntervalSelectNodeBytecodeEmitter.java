/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2021-2026 ishland
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.ishland.c2me.opts.dfc.common.gen.jvm.emitters.misc;

import com.ishland.c2me.opts.dfc.common.ast.misc.IntervalSelectNode;
import com.ishland.c2me.opts.dfc.common.gen.jvm.BytecodeEmitter;
import com.ishland.c2me.opts.dfc.common.gen.jvm.BytecodeGen;
import com.ishland.c2me.opts.dfc.common.gen.meta.ValuesMethodDefD;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;

import java.util.Arrays;
import java.util.function.Consumer;

public class IntervalSelectNodeBytecodeEmitter implements BytecodeEmitter<IntervalSelectNode> {

    public static final IntervalSelectNodeBytecodeEmitter INSTANCE = new IntervalSelectNodeBytecodeEmitter();

    private IntervalSelectNodeBytecodeEmitter() {
    }

    @Override
    public void doBytecodeGenSingle(IntervalSelectNode node, BytecodeGen.Context context, InstructionAdapter m, BytecodeGen.Context.LocalVarConsumer localVarConsumer) {
        ValuesMethodDefD inputMethod = context.newSingleMethod(node.input);
        ValuesMethodDefD[] delegates = Arrays.stream(node.functions).map(context::newSingleMethod).toArray(ValuesMethodDefD[]::new);

        int inputValue = localVarConsumer.createLocalVariable("inputValue", Type.DOUBLE_TYPE.getDescriptor());
        context.callDelegateSingle(m, inputMethod);
        m.store(inputValue, Type.DOUBLE_TYPE);

        Label end = new Label();
        genBinarySearch(
                node.thresholds,
                m,
                end,
                () -> m.load(inputValue, Type.DOUBLE_TYPE),
                index -> context.callDelegateSingle(m, delegates[index]),
                0,
                node.thresholds.length
        );
        m.visitLabel(end);
        m.areturn(Type.DOUBLE_TYPE);
    }

    @Override
    public void doBytecodeGenMulti(IntervalSelectNode node, BytecodeGen.Context context, InstructionAdapter m, BytecodeGen.Context.LocalVarConsumer localVarConsumer) {
        ValuesMethodDefD inputMulti = context.newMultiMethod(node.input);
        ValuesMethodDefD[] delegates = Arrays.stream(node.functions).map(context::newSingleMethod).toArray(ValuesMethodDefD[]::new);

        context.callDelegateMulti(m, inputMulti);

        context.doCountedLoop(m, localVarConsumer, idx -> {
            // target of the astore at the end of the loop body
            m.load(1, InstructionAdapter.OBJECT_TYPE);
            m.load(idx, Type.INT_TYPE);

            Label end = new Label();
            genBinarySearch(
                    node.thresholds,
                    m,
                    end,
                    () -> {
                        m.load(1, InstructionAdapter.OBJECT_TYPE);
                        m.load(idx, Type.INT_TYPE);
                        m.aload(Type.DOUBLE_TYPE);
                    },
                    index -> context.callDelegateSingleFromMulti(m, delegates[index], idx),
                    0,
                    node.thresholds.length
            );
            m.visitLabel(end);

            m.astore(Type.DOUBLE_TYPE);
        });

        m.areturn(Type.VOID_TYPE);
    }

    /**
     * Emits {@code input < thresholds[i] ? functions[i] : ... : functions[thresholds.length]} as a binary search
     * over {@code [fromIndex, toIndex)}, leaving the selected function's value on the stack and jumping to
     * {@code end} afterwards.
     */
    private static void genBinarySearch(
            double[] thresholds,
            InstructionAdapter m,
            Label end,
            Runnable pushInput,
            Consumer<Integer> callDelegate,
            int fromIndex,
            int toIndex
    ) {
        int mid = (fromIndex + toIndex - 1) >>> 1;
        double midVal = thresholds[mid];

        Label geLabel = new Label();
        pushInput.run();
        m.dconst(midVal);
        m.cmpg(Type.DOUBLE_TYPE);
        m.ifge(geLabel);

        if (fromIndex == mid) {
            callDelegate.accept(fromIndex);
            m.goTo(end);
        } else {
            genBinarySearch(thresholds, m, end, pushInput, callDelegate, fromIndex, mid);
        }

        m.visitLabel(geLabel);
        if (mid + 1 == toIndex) {
            callDelegate.accept(toIndex);
            m.goTo(end);
        } else {
            genBinarySearch(thresholds, m, end, pushInput, callDelegate, mid + 1, toIndex);
        }
    }
}
