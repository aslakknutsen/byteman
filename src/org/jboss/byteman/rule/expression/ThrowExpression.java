/*
* JBoss, Home of Professional Open Source
* Copyright 2008, Red Hat Middleware LLC, and individual contributors
* by the @authors tag. See the copyright.txt in the distribution for a
* full listing of individual contributors.
*
* This is free software; you can redistribute it and/or modify it
* under the terms of the GNU Lesser General Public License as
* published by the Free Software Foundation; either version 2.1 of
* the License, or (at your option) any later version.
*
* This software is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
* Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public
* License along with this software; if not, write to the Free
* Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
* 02110-1301 USA, or see the FSF site: http://www.fsf.org.
*
* @authors Andrew Dinn
*/
package org.jboss.byteman.rule.expression;

import org.jboss.byteman.rule.compiler.CompileContext;
import org.jboss.byteman.rule.type.Type;
import org.jboss.byteman.rule.type.TypeGroup;
import org.jboss.byteman.rule.exception.TypeException;
import org.jboss.byteman.rule.exception.ExecuteException;
import org.jboss.byteman.rule.exception.ThrowException;
import org.jboss.byteman.rule.exception.CompileException;
import org.jboss.byteman.rule.Rule;
import org.jboss.byteman.rule.helper.HelperAdapter;
import org.jboss.byteman.rule.grammar.ParseNode;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.StringWriter;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * Expression which implements a throw from a rule action but only where the thrown exception is
 * declared by the trigger method or is a runtime exception which does nto need ot be declared
 */
public class ThrowExpression extends Expression
{
    private String typeName;
    private List<Expression> arguments;
    private List<Type> argumentTypes;
    private List<Type> paramTypes;
    private Constructor constructor;

    public ThrowExpression(Rule rule, ParseNode token, List<Expression> arguments) {
        super(rule, Type.UNDEFINED, token);
        this.typeName = token.getText();
        this.arguments = arguments;
        this.argumentTypes = null;
        this.constructor = null;
    }
    /**
     * verify that variables mentioned in this expression are actually available in the supplied
     * bindings list and infer/validate the type of this expression or its subexpressions
     * where possible
     *
     * @return true if all variables in this expression are bound and no type mismatches have
     *         been detected during inference/validation.
     */
    public void bind() throws TypeException
    {
        // check that the recipient and argument expressions have valid bindings

        Iterator<Expression> iterator = arguments.iterator();

        while (iterator.hasNext()) {
            iterator.next().bind();
        }
    }

    /**
     * ensure that all type references in the expression and its component expressions
     * can be resolved, that the type of the expression is well-defined and that it is
     * compatible with the type expected in the context in which it occurs.
     *
     * @param expected  the type expected for the expression in the contxt in which it occurs. this
     *                  may be void but shoudl not be undefined at the point where type checking is performed.
     * @return
     * @throws org.jboss.byteman.rule.exception.TypeException
     *
     */
    public Type typeCheck(Type expected) throws TypeException {
        // check the exception type is defined and then look for a relevant constructor

        TypeGroup typeGroup = getTypeGroup();

        type = Type.dereference(typeGroup.create(typeName));

        if (type == null || type.isUndefined()) {
            throw new TypeException("ThrowExpression.typeCheck : unknown exception type " + typeName + getPos());
        }

        if (!Throwable.class.isAssignableFrom(type.getTargetClass())) {
            throw new TypeException("ThrowExpression.typeCheck : not an exception type " + typeName  + getPos());
        }

        Class clazz = type.getTargetClass();
        // if we can find a unique method then we can use it to type the parameters
        // otherwise we do it the hard way
        int arity = arguments.size();
        Constructor[] constructors = clazz.getConstructors();
        List<Constructor> candidates = new ArrayList<Constructor>();
        boolean duplicates = false;

        for (Constructor constructor : constructors) {
            if (constructor.getParameterTypes().length == arity) {
                candidates.add(constructor);
            }
        }

        argumentTypes = new ArrayList<Type>();

        // check each argument in turn -- if all candidates have the same argument type then
        // use that as the type to check against
        for (int i = 0; i < arguments.size() ; i++) {
            if (candidates.isEmpty()) {
                throw new TypeException("ThrowExpression.typeCheck : invalid constructor for target class " + typeName + getPos());
            }

            // TODO get and prune operations do not allow for coercion but type check does!
            // e.g. the parameter type may be int and the arg type float
            // or the parameter type may be String and the arg type class Foo
            // reimplement this using type inter-assignability to do the pruning

            Class candidateClass = getCandidateArgClass(candidates, i);
            Type candidateType;
            if (candidateClass != null) {
                candidateType = typeGroup.ensureType(candidateClass);
            } else {
                candidateType = Type.UNDEFINED;
            }
            Type argType = arguments.get(i).typeCheck(candidateType);
            argumentTypes.add(argType);
            if (candidateType == Type.UNDEFINED) {
                // we had several constructors to choose from
                candidates = pruneCandidates(candidates, i, argType.getTargetClass());
            }
        }

        if (candidates.isEmpty()) {
            throw new TypeException("ThrowExpression.typeCheck : invalid constructor for target class " + typeName + getPos());
        }

        if (candidates.size() > 1) {
            throw new TypeException("ThrowExpression.typeCheck : ambiguous constructor signature for target class " + typeName + getPos());
        }

        constructor = candidates.get(0);

        // make sure we know the formal parameter types and have included them in the typegroup

        paramTypes = new ArrayList<Type>();
        Class<?>[] paramClasses = constructor.getParameterTypes();

        for (int i = 0; i < arguments.size() ; i++) {
            paramTypes.add(typeGroup.ensureType(paramClasses[i]));
        }

        // expected type should always be void since throw can only occur as a top level action
        // however, we need to be sure that the trigering method throws this exception type or
        // else that it is a subtype of runtime exception

        if (RuntimeException.class.isAssignableFrom(type.getTargetClass())) {
            return type;
        } else {
            Iterator<Type> iterator = typeGroup.getExceptionTypes().iterator();
            while (iterator.hasNext()) {
                Type exceptionType = iterator.next();
                if (Type.dereference(exceptionType).isAssignableFrom(type)) {
                    // ok we foudn a suitable declaration for the exception
                    return type;
                }
            }
            // didn't find a suitable type in the method type list
            throw new TypeException("ThrowExpression.typeCheck : exception type not declared by trigger method "  + typeName + getPos());
        }
    }

    public Class getCandidateArgClass(List<Constructor> candidates, int argIdx)
    {
        Class argClazz = null;

        for (Constructor c : candidates) {
            Class nextClazz = c.getParameterTypes()[argIdx];
            if (argClazz == null) {
                argClazz = nextClazz;
            } else if (argClazz != nextClazz) {
                return null;
            }
        }

        return argClazz;
    }

    public List<Constructor> pruneCandidates(List<Constructor> candidates, int argIdx, Class argClazz)
    {
        for (int i = 0; i < candidates.size();) {
            Constructor c = candidates.get(i);
            Class nextClazz = c.getParameterTypes()[argIdx];
            if (nextClazz != argClazz) {
                candidates.remove(i);
            } else {
                i++;
            }
        }
        return candidates;
    }

    /**
     * evaluate the expression by interpreting the expression tree
     *
     * @param helper an execution context associated with the rule whcih contains a map of
     *               current bindings for rule variables and another map of their declared types both of which
     *               are indexed by varoable name. This includes entries for the helper (name "-1"), the
     *               recipient if the trigger method is not static (name "0") and the trigger method arguments
     *               (names "1", ...)
     * @return the result of evaluation as an Object
     * @throws org.jboss.byteman.rule.exception.ExecuteException
     *
     */
    public Object interpret(HelperAdapter helper) throws ExecuteException {
        int l = arguments.size();
        int i;
        Object[] callArgs = new Object[l];
        for (i =0; i < l; i++) {
            callArgs[i] = arguments.get(i).interpret(helper);
        }
        try {
            Throwable th = (Throwable) constructor.newInstance(callArgs);
            ThrowException thex = new ThrowException(th);
            throw thex;
        } catch (InstantiationException e) {
            throw new ExecuteException("ThrowExpression.interpret : unable to instantiate exception class " + typeName + getPos(), e);
        } catch (IllegalAccessException e) {
            throw new ExecuteException("ThrowExpression.interpret : unable to access exception class " + typeName + getPos(), e);
        } catch (InvocationTargetException e) {
            throw new ExecuteException("ThrowExpression.interpret : unable to invoke exception class constructor for " + typeName + getPos(), e);
        }
    }

    public void compile(MethodVisitor mv, CompileContext compileContext) throws CompileException
    {
        int currentStack = compileContext.getStackCount();
        int expected = 1;
        int extraParams = 0;

        // ok, we need to create the thrown exception instance and then
        // initialise it.

        // create the thrown exception instance -- adds 1 to stack
        String exceptionClassName = type.getInternalName();
        mv.visitTypeInsn(Opcodes.NEW, exceptionClassName);
        compileContext.addStackCount(1);
        // copy the exception so we can init it
        mv.visitInsn(Opcodes.DUP);
        compileContext.addStackCount(1);

        int argCount = arguments.size();

        // stack each of the arguments to the constructor
        for (int i = 0; i < argCount; i++) {
            Type argType = argumentTypes.get(i);
            Type paramType = paramTypes.get(i);
            int paramCount = (paramType.getNBytes() > 4 ? 2 : 1);

            // track extra storage used after type conversion
            extraParams += (paramCount);
            arguments.get(i).compile(mv, compileContext);
            compileTypeConversion(argType, paramType, mv, compileContext);
        }

        // construct the exception
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, exceptionClassName, "<init>", getDescriptor());

        // modify the stack height to account for the removed exception and params
        compileContext.addStackCount(-(extraParams+1));

        if (compileContext.getStackCount() != currentStack + expected) {
            throw new CompileException("ThrowExpression.compile : invalid stack height " + compileContext.getStackCount() + " expecting " + (currentStack + expected));
        }
        // now create a ThrowException to wrap the user exception
        // create the thrown exception instance -- adds 1 to stack [UE] --> [UE, THE]
        exceptionClassName = "org/jboss/byteman/rule/exception/ThrowException";
        mv.visitTypeInsn(Opcodes.NEW, exceptionClassName);
        compileContext.addStackCount(1);
        // copy the ThrowException so we can init it [UE, THE] --> [THE, UE, THE]
        mv.visitInsn(Opcodes.DUP_X1);
        compileContext.addStackCount(1);
        // reverse the order of the top two words  [THE, UE, THE] --> [THE, THE, UE]
        mv.visitInsn(Opcodes.SWAP);
        // construct the exception [THE, THE, UE] --> [UE]
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, exceptionClassName, "<init>", "(Ljava/lang/Throwable;)V");
        // we should now have just the ThrowException on the stack
        compileContext.addStackCount(-2);
        if (compileContext.getStackCount() != currentStack + expected) {
            throw new CompileException("ThrowExpression.compile : invalid stack height " + compileContext.getStackCount() + " expecting " + (currentStack + expected));
        }

        // now throw the exception and decrement the stack height

        mv.visitInsn(Opcodes.ATHROW);
        compileContext.addStackCount(-1);
    }

    private String getDescriptor()
    {
        StringBuffer buffer = new StringBuffer();
        buffer.append("(");
        int nParams = paramTypes.size();
        for (int i = 0; i < nParams; i++) {
            buffer.append(paramTypes.get(i).getInternalName(true, true));
        }
        buffer.append(")V");
        return buffer.toString();
    }

    public void writeTo(StringWriter stringWriter) {
        stringWriter.write("throw ");
        if (type == null || Type.UNDEFINED == type) {
            stringWriter.write(typeName);
        } else {
            stringWriter.write(type.getName());
        }
        String separator = "";
        stringWriter.write("(");
        for (Expression argument : arguments) {
            stringWriter.write(separator);
            argument.writeTo(stringWriter);
            separator = ",";
        }
        stringWriter.write(")");

    }
}
