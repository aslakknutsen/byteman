/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
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

package org.jboss.byteman.rule.compiler;

import org.jboss.byteman.rule.Rule;
import org.jboss.byteman.rule.binding.Binding;
import org.jboss.byteman.rule.binding.Bindings;
import org.jboss.byteman.rule.exception.CompileException;
import org.jboss.byteman.agent.Transformer;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.*;

import java.lang.reflect.Constructor;
import java.util.Iterator;

/**
 * A class which compiles a rule by generating a subclass of the rule's helperClass which implements
 * the HelperAdapter interface
 */
public class Compiler implements Opcodes
{
    public static Class getHelperAdapter(Rule rule, Class helperClass, boolean compileToBytecode) throws CompileException
    {
        Class adapterClass;
        // ok we have to create the adapter class

        // n.b. we don't bother synchronizing here -- if another rule is racing to create an adapter
        // in parallel we don't really care about generating two of them -- we can use whichever
        // one gets installed last

        try {
            String helperName = Type.getInternalName(helperClass);
            String compiledHelperName;

            // we put the helper in the
            if (compileToBytecode) {
                compiledHelperName = helperName + "_HelperAdapter_Compiled_" + nextId();
            } else {
                compiledHelperName = helperName + "_HelperAdapter_Interpreted_" + nextId();
            }

            byte[] classBytes = compileBytes(rule, helperClass, helperName, compiledHelperName, compileToBytecode);
            String externalName = compiledHelperName.replace('/', '.');
            // dump the compiled class bytes if required
            Transformer.maybeDumpClass(externalName, classBytes);
            // ensure the class is loaded
            // think we need to load the generated helper using the class loader of the trigger class
            ClassLoader loader = rule.getLoader();
            adapterClass = loadHelperAdapter(loader, externalName, classBytes);
        } catch(CompileException ce) {
            throw ce;
        } catch (Throwable th) {
            if (compileToBytecode) {
                throw new CompileException("Compiler.createHelperAdapter : exception creating compiled helper adapter for " + helperClass.getName(), th);
            } else {
                throw new CompileException("Compiler.createHelperAdapter : exception creating interpreted helper adapter for " + helperClass.getName(), th);
            }
        }

        return adapterClass;
    }

    private static byte[] compileBytes(Rule rule, Class helperClass, String helperName, String compiledHelperName, boolean compileToBytecode) throws Exception
    {
        ClassWriter cw = new ClassWriter(0);
        FieldVisitor fv;
        MethodVisitor mv;
        AnnotationVisitor av0;
        // create the class as a subclass of the rule helper class, appending Compiled to the front
        // of the class name and a unique number to the end of the class helperName
        // also ensure it implements the HelperAdapter interface
        //
        // public class foo.bar.Compiled_<helper>_<NNN> extends foo.bar.<helper> implements HelperAdapter

        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, compiledHelperName, null, helperName, new String[] { "org/jboss/byteman/rule/helper/HelperAdapter" });
        {
        // we need a Hashmap field to hold the bindings
        //
        // private HashMap<String, Object> bindingMap;

        fv = cw.visitField(ACC_PRIVATE, "bindingMap", "Ljava/util/HashMap;", "Ljava/util/HashMap<Ljava/lang/String;Ljava/lang/Object;>;", null);
        fv.visitEnd();
        }
        {
        // and a rule field to hold the rule
        //
        // private Rule rule;

        fv = cw.visitField(ACC_PRIVATE, "rule", "Lorg/jboss/byteman/rule/Rule;", "Lorg/jboss/byteman/rule/Rule;", null);
        fv.visitEnd();
        }
        {
        // we need a constructor which takes a Rule as argument
        // if the helper implements a constructor which takes a Rule as argument then we invoke it
        // otherwise we invoke the empty helper constructor

        Constructor superConstructor = null;
        try {
            superConstructor = helperClass.getDeclaredConstructor(Rule.class);
        } catch (NoSuchMethodException e) {
            // hmm, ok see if there is an empty constructor
        } catch (SecurityException e) {
            throw new CompileException("Compiler.compileBytes : unable to access constructor for helper class " + helperClass.getCanonicalName());
        }
        boolean superWantsRule = (superConstructor != null);
        if (!superWantsRule) {
            try {
                superConstructor = helperClass.getDeclaredConstructor();
            } catch (NoSuchMethodException e) {
                throw new CompileException("Compiler.compileBytes : no valid constructor found for helper class " + helperClass.getCanonicalName());
            } catch (SecurityException e) {
                throw new CompileException("Compiler.compileBytes : unable to access constructor for helper class " + helperClass.getCanonicalName());
            }
        }
        //
        //  public Compiled<helper>_<NNN>()Rule rule)
        mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Lorg/jboss/byteman/rule/Rule;)V", null, null);
        mv.visitCode();
        // super();
        //
        // or
        //
        // super(Rule);
        if (superWantsRule) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESPECIAL, helperName, "<init>", "(Lorg/jboss/byteman/rule/Rule;)V");
        } else {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, helperName, "<init>", "()V");
        }
        // bindingMap = new HashMap<String, Object);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(NEW, "java/util/HashMap");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap", "<init>", "()V");
        mv.visitFieldInsn(PUTFIELD, compiledHelperName, "bindingMap", "Ljava/util/HashMap;");
        // this.rule = rule
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
        // return;
        mv.visitInsn(RETURN);
        mv.visitMaxs(3, 2);
        mv.visitEnd();
        }
        {
            // create the execute method
            //
            // public void execute(Bindings bindings, Object recipient, Object[] args) throws ExecuteException
            mv = cw.visitMethod(ACC_PUBLIC, "execute", "(Ljava/lang/Object;[Ljava/lang/Object;)V", null, new String[] { "org/jboss/byteman/rule/exception/ExecuteException" });
            mv.visitCode();
            // if (Transformer.isVerbose())
            mv.visitMethodInsn(INVOKESTATIC, "org/jboss/byteman/agent/Transformer", "isVerbose", "()Z");
            Label l0 = new Label();
            mv.visitJumpInsn(IFEQ, l0);
            // then
            // System.out.println(rule.getName() + " execute");
            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Rule", "getName", "()Ljava/lang/String;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
            mv.visitLdcInsn(" execute()");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V");
            // end if
            mv.visitLabel(l0);

            Bindings bindings = rule.getBindings();
            Iterator<Binding> iterator = bindings.iterator();

            while (iterator.hasNext()) {
                Binding binding = iterator.next();
                String name = binding.getName();
                if (binding.isAlias()) {
                    // lookups and updates will use the aliased name
                    continue;
                }
                if (binding.isHelper()) {
                    // bindingMap.put(name, this);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitFieldInsn(GETFIELD, compiledHelperName, "bindingMap", "Ljava/util/HashMap;");
                    mv.visitLdcInsn(name);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/HashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                    mv.visitInsn(POP);
                } else if (binding.isRecipient()) {
                    // bindingMap.put(name, recipient);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitFieldInsn(GETFIELD, compiledHelperName, "bindingMap", "Ljava/util/HashMap;");
                    mv.visitLdcInsn(name);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/HashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                    mv.visitInsn(POP);
                // } else if (binding.isParam() || binding.isLocalVar() || binding.isReturn() ||
                //             binding.isThrowable() || binding.isParamCount() || binding.isParamArray()) {
                } else if (!binding.isBindVar()) {
                    // bindingMap.put(name, args[binding.getCallArrayIndex()]);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitFieldInsn(GETFIELD, compiledHelperName, "bindingMap", "Ljava/util/HashMap;");
                    mv.visitLdcInsn(name);
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitLdcInsn(binding.getCallArrayIndex());
                    mv.visitInsn(AALOAD);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/HashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                    mv.visitInsn(POP);
                }
            }

            // execute0()
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, compiledHelperName, "execute0", "()V");

            // now restore update bindings

            iterator = bindings.iterator();

            while (iterator.hasNext()) {
                Binding binding = iterator.next();
                if (binding.isAlias()) {
                    continue;
                }
                String name = binding.getName();

                if (binding.isUpdated()) {
                    // if (binding.isParam() || binding.isLocalVar() || binding.isReturn()) {
                    if (!binding.isBindVar()) {
                        int idx = binding.getCallArrayIndex();
                        // Object value = bindingMap.get(name);
                        // args[idx] = value;
                        mv.visitVarInsn(ALOAD, 2); // args
                        mv.visitLdcInsn(idx);
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitFieldInsn(GETFIELD, compiledHelperName, "bindingMap", "Ljava/util/HashMap;");
                        mv.visitLdcInsn(name);
                        mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/HashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
                        mv.visitInsn(AASTORE);
                    }
                }
            }

            // return
            mv.visitInsn(RETURN);
            mv.visitMaxs(4, 3);
            mv.visitEnd();
        }
        {
        // create the setBinding method
        //
        // public void setBinding(String name, Object value)
        mv = cw.visitMethod(ACC_PUBLIC, "setBinding", "(Ljava/lang/String;Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        //  bindingMap.put(name, value);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, compiledHelperName, "bindingMap", "Ljava/util/HashMap;");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/HashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        mv.visitInsn(POP);
        // return
        mv.visitInsn(RETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
        }
        {
        // create the getBinding method
        //
        // public Object getBinding(String name)
        mv = cw.visitMethod(ACC_PUBLIC, "getBinding", "(Ljava/lang/String;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        // {TOS} <== bindingMap.get(name);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, compiledHelperName, "bindingMap", "Ljava/util/HashMap;");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/HashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
        // return {TOS}
        mv.visitInsn(ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        }
        {
        // create the getName method
        //
        // public String getName()
        mv = cw.visitMethod(ACC_PUBLIC, "getName", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        // {TOS} <== rule.getName()
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Rule", "getName", "()Ljava/lang/String;");
        // return {TOS}
        mv.visitInsn(ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        }
        // create the getAccessibleField method
        //
        // public Object getAccessibleField(Object owner, int fieldIndex)
        {
        mv = cw.visitMethod(ACC_PUBLIC, "getAccessibleField", "(Ljava/lang/Object;I)Ljava/lang/Object;", null, null);
        mv.visitCode();
        // {TOS} <== rule.getAccessibleField(owner, fieldIndex);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Rule", "getAccessibleField", "(Ljava/lang/Object;I)Ljava/lang/Object;");
        // return {TOS}
        mv.visitInsn(ARETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
        }

        // create the setAccessibleField method
        //
        // public void setAccessibleField(Object owner, Object value, int fieldIndex)
        // rule.setAccessibleField(owner, value, fieldIndex);
        {
        mv = cw.visitMethod(ACC_PUBLIC, "setAccessibleField", "(Ljava/lang/Object;Ljava/lang/Object;I)V", null, null);
        mv.visitCode();
        // rule.setAccessibleField(owner, value, fieldIndex);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Rule", "setAccessibleField", "(Ljava/lang/Object;Ljava/lang/Object;I)V");
        // return
        mv.visitInsn(RETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();
        }

        // create the invokeAccessibleMethod method
        //
        // public Object invokeAccessibleMethod(Object target, Object[] args, int methodIndex)
        // {TOS} <==  rule.invokeAccessibleMethod(target, args, methodIndex);
        {
        mv = cw.visitMethod(ACC_PUBLIC, "invokeAccessibleMethod", "(Ljava/lang/Object;[Ljava/lang/Object;I)Ljava/lang/Object;", null, null);
        mv.visitCode();
        // rule.invokeAccessibleMethod(target, args, fieldIndex);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Rule", "invokeAccessibleMethod", "(Ljava/lang/Object;[Ljava/lang/Object;I)Ljava/lang/Object;");
            // return {TOS}
        mv.visitInsn(ARETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();
        }
        if (compileToBytecode) {
            // we generate a single execute0 method if we want to run compiled and get
            // the event, condiiton and action to insert the relevant bytecode to implement
            // bind(), test() and fire()

            CompileContext compileContext = new CompileContext(rule.getLine());
            {
            // create the execute0() method
            //
            // private void execute0()
            mv = cw.visitMethod(ACC_PRIVATE, "execute0", "()V", null, new String[] { "org/jboss/byteman/rule/exception/ExecuteException" });
            mv.visitCode();
            compileContext.addLocalCount(3); // for this and 2 object args
            // bind();
            rule.getEvent().compile(mv, compileContext);
            // if (test())
            rule.getCondition().compile(mv, compileContext);
            Label l0 = new Label();
            mv.visitJumpInsn(IFEQ, l0);
            compileContext.addStackCount(-1);
            // then
            rule.getAction().compile(mv, compileContext);
            // fire();
            // end if
            mv.visitLabel(l0);
            // return
            mv.visitInsn(RETURN);
            // need to specify correct Maxs values
            mv.visitMaxs(compileContext.getStackMax(), compileContext.getLocalMax());
            mv.visitEnd();
            }
        } else {
            // we generate the following methods if we want to run interpreted
            {
            // create the execute0() method
            //
            // private void execute0()
            mv = cw.visitMethod(ACC_PRIVATE, "execute0", "()V", null, new String[] { "org/jboss/byteman/rule/exception/ExecuteException" });
            mv.visitCode();
            // bind();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, compiledHelperName, "bind", "()V");
            // if (test())
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, compiledHelperName, "test", "()Z");
            Label l0 = new Label();
            mv.visitJumpInsn(IFEQ, l0);
            // then
            // fire();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, compiledHelperName, "fire", "()V");
            // end if
            mv.visitLabel(l0);
            // return
            mv.visitInsn(RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
            }
            {
            // create the bind method
            //
            // private void bind()
            mv = cw.visitMethod(ACC_PRIVATE, "bind", "()V", null, new String[] { "org/jboss/byteman/rule/exception/ExecuteException" });
            mv.visitCode();
            // rule.getEvent().interpret(this);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Rule", "getEvent", "()Lorg/jboss/byteman/rule/Event;");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Event", "interpret", "(Lorg/jboss/byteman/rule/helper/HelperAdapter;)Ljava/lang/Object;");
            mv.visitInsn(RETURN);
            mv.visitMaxs(2, 1);
            mv.visitEnd();
            }
            {
            // create the test method
            //
            // private boolean test()
            mv = cw.visitMethod(ACC_PRIVATE, "test", "()Z", null, new String[] { "org/jboss/byteman/rule/exception/ExecuteException" });
            mv.visitCode();
            // {TOS} <== rule.getCondition().interpret(this);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Rule", "getCondition", "()Lorg/jboss/byteman/rule/Condition;");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Condition", "interpret", "(Lorg/jboss/byteman/rule/helper/HelperAdapter;)Ljava/lang/Object;");
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
            // unbox the returned Boolean
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z");
            // return {TOS}
            mv.visitInsn(IRETURN);
            mv.visitMaxs(2, 1);
            mv.visitEnd();
            }
            {
            // create the fire method
            //
            // private void fire()
            mv = cw.visitMethod(ACC_PRIVATE, "fire", "()V", null, new String[] { "org/jboss/byteman/rule/exception/ExecuteException" });
            mv.visitCode();
            // rule.getAction().interpret(this);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Rule", "getAction", "()Lorg/jboss/byteman/rule/Action;");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Action", "interpret", "(Lorg/jboss/byteman/rule/helper/HelperAdapter;)Ljava/lang/Object;");
            // return
            mv.visitInsn(RETURN);
            mv.visitMaxs(2, 1);
            mv.visitEnd();
            }
        }

        cw.visitEnd();

        return cw.toByteArray();
    }

    private static int nextId = 0;

    private static synchronized int nextId()
    {
        return ++nextId;
    }

    /**
     * this is a classloader used to define classes from bytecode
     */
    private static class ClassbyteClassLoader extends ClassLoader
    {
        ClassbyteClassLoader(ClassLoader cl)
        {
            super(cl);
        }

        public Class addClass(String name, byte[] bytes)
                throws ClassFormatError
        {
            Class cl = defineClass(name, bytes, 0, bytes.length);
            resolveClass(cl);

            return cl;
        }
    }

    /**
     * dynamically load and return a generated helper adapter classes using a custom classloader derived from the
     * trigger class's loader
     * @param triggerClassLoader the class loader of the trigger class which has been matched with this
     * helper class's rule
     * @param helperAdapterName the name of the helper adaptter class to be loaded
     * @param classBytes the byte array defining the class
     * @return
     */
    public static Class<?> loadHelperAdapter(ClassLoader triggerClassLoader, String helperAdapterName, byte[] classBytes)
    {
        // create the helper class in a classloader derived from the trigger class
        // this allows the injected code to refer to the triggger class type and related
        // application types. the defalt helper will be accessible because it is loaded bby the
        // ootstrap loader. custom helpers need to be made avvailable to the applicattion either
        // by deployng them with it or by locating them in the JVM classpath.
        ClassbyteClassLoader loader = new ClassbyteClassLoader(triggerClassLoader);

        return loader.addClass(helperAdapterName, classBytes);
    }

}
