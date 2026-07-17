package com.ishland.c2me.opts.dfc.common.ast.misc;

import com.ishland.c2me.opts.dfc.common.ast.AstNode;
import com.ishland.c2me.opts.dfc.common.ast.AstTransformer;
import com.ishland.c2me.opts.dfc.common.ast.EvalType;
import com.ishland.c2me.opts.dfc.common.gen.BytecodeGen;
import java.util.Arrays;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;

public class IntervalSelectNode implements AstNode {
    public final AstNode input;
    public final double[] thresholds;
    public final AstNode[] functions;

    public IntervalSelectNode(AstNode input, double[] thresholds, AstNode[] functions) {
        this.input = input;
        this.thresholds = thresholds;
        this.functions = functions;
    }

    public double evalSingle(int x, int y, int z, EvalType type) {
        double v = this.input.evalSingle(x, y, z, type);

        for(int i = 0; i < this.thresholds.length; ++i) {
            if (v < this.thresholds[i]) {
                return this.functions[i].evalSingle(x, y, z, type);
            }
        }

        return this.functions[this.functions.length - 1].evalSingle(x, y, z, type);
    }

    public void evalMulti(double[] res, int[] x, int[] y, int[] z, EvalType type) {
        this.input.evalMulti(res, x, y, z, type);

        for(int i = 0; i < res.length; ++i) {
            res[i] = this.evalIndexed(res[i], x[i], y[i], z[i], type);
        }

    }

    private double evalIndexed(double v, int x, int y, int z, EvalType type) {
        for(int i = 0; i < this.thresholds.length; ++i) {
            if (v < this.thresholds[i]) {
                return this.functions[i].evalSingle(x, y, z, type);
            }
        }

        return this.functions[this.functions.length - 1].evalSingle(x, y, z, type);
    }

    public AstNode[] getChildren() {
        AstNode[] nodes = new AstNode[this.functions.length + 1];
        nodes[0] = this.input;
        System.arraycopy(this.functions, 0, nodes, 1, this.functions.length);
        return nodes;
    }

    public AstNode transform(AstTransformer transformer) {
        boolean changed = false;
        AstNode transformedInput = this.input.transform(transformer);
        if (transformedInput != this.input) {
            changed = true;
        }

        AstNode[] transformedFunctions = (AstNode[])this.functions.clone();

        for(int i = 0; i < transformedFunctions.length; ++i) {
            AstNode transformedFunction = transformedFunctions[i];
            transformedFunctions[i] = transformedFunction.transform(transformer);
            if (transformedFunctions[i] != transformedFunction) {
                changed = true;
            }
        }

        return !changed ? transformer.transform(this) : transformer.transform(new IntervalSelectNode(transformedInput, (double[])this.thresholds.clone(), transformedFunctions));
    }

    public void doBytecodeGenSingle(BytecodeGen.Context context, InstructionAdapter m, BytecodeGen.Context.LocalVarConsumer localVarConsumer) {
        String inputMethod = context.newSingleMethod(this.input);
        Label endLabel = new Label();
        context.callDelegateSingle(m, inputMethod);
        String[] delegates = Arrays.stream(this.functions).map(context::newSingleMethod).toArray(String[]::new);
        genBinarySearch(this.thresholds, delegates, context, m, endLabel, 0, this.thresholds.length);
        m.visitLabel(endLabel);
        m.areturn(Type.DOUBLE_TYPE);
    }

    private static void genBinarySearch(double[] thresholds, String[] delegates, BytecodeGen.Context context, InstructionAdapter m, Label endLabel, int fromIndex, int toIndex) {
        int mid = fromIndex + toIndex - 1 >>> 1;
        double midVal = thresholds[mid];
        Label geLabel = new Label();
        m.dup2();
        m.dconst(midVal);
        m.cmpg(Type.DOUBLE_TYPE);
        m.ifge(geLabel);
        if (fromIndex == mid) {
            m.pop2();
            context.callDelegateSingle(m, delegates[fromIndex]);
            m.goTo(endLabel);
        } else {
            genBinarySearch(thresholds, delegates, context, m, endLabel, fromIndex, mid);
        }

        m.visitLabel(geLabel);
        if (mid + 1 == toIndex) {
            m.pop2();
            context.callDelegateSingle(m, delegates[toIndex]);
            m.goTo(endLabel);
        } else {
            genBinarySearch(thresholds, delegates, context, m, endLabel, mid + 1, toIndex);
        }

    }

    public void doBytecodeGenMulti(BytecodeGen.Context context, InstructionAdapter m, BytecodeGen.Context.LocalVarConsumer localVarConsumer) {
        context.delegateToSingle(m, localVarConsumer, this);
        m.areturn(Type.VOID_TYPE);
    }

    public boolean equals(Object o) {
        if (o != null && this.getClass() == o.getClass()) {
            IntervalSelectNode that = (IntervalSelectNode)o;
            return this.input.equals(that.input) && Arrays.equals(this.thresholds, that.thresholds) && Arrays.equals(this.functions, that.functions);
        } else {
            return false;
        }
    }

    public int hashCode() {
        int result = 1;
        result = 31 * result + this.getClass().hashCode();
        result = 31 * result + this.input.hashCode();
        result = 31 * result + Arrays.hashCode(this.thresholds);
        result = 31 * result + Arrays.hashCode(this.functions);
        return result;
    }

    public boolean relaxedEquals(AstNode o) {
        if (o != null && this.getClass() == o.getClass()) {
            IntervalSelectNode that = (IntervalSelectNode)o;
            if (!this.input.relaxedEquals(that.input) || !Arrays.equals(this.thresholds, that.thresholds)) {
                return false;
            } else if (this.functions.length != that.functions.length) {
                return false;
            } else {
                for(int i = 0; i < this.functions.length; ++i) {
                    if (!this.functions[i].relaxedEquals(that.functions[i])) {
                        return false;
                    }
                }

                return true;
            }
        } else {
            return false;
        }
    }

    public int relaxedHashCode() {
        int result = 1;
        result = 31 * result + this.getClass().hashCode();
        result = 31 * result + this.input.relaxedHashCode();
        result = 31 * result + Arrays.hashCode(this.thresholds);

        for(AstNode function : this.functions) {
            result = 31 * result + function.relaxedHashCode();
        }

        return result;
    }
}
