/*
* JBoss, Home of Professional Open Source
* Copyright 2008-9, Red Hat Middleware LLC, and individual contributors
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
package org.jboss.byteman.agent.adapter;

import org.jboss.byteman.rule.Rule;
import org.jboss.byteman.rule.type.TypeHelper;
import org.jboss.byteman.agent.Location;
import org.jboss.byteman.agent.RuleScript;
import org.jboss.byteman.agent.TransformContext;
import org.objectweb.asm.*;

/**
 * asm Adapter class used to add a rule event trigger call to a method of som egiven class
 */
public class AccessTriggerAdapter extends RuleTriggerAdapter
{
    public AccessTriggerAdapter(ClassVisitor cv, TransformContext transformContext, String ownerClass,
                                String fieldName, int flags, int count, boolean whenComplete)
    {
        super(cv, transformContext);
        this.ownerClass = ownerClass;
        this.fieldName = fieldName;
        this.flags = flags;
        this.count = count;
        this.whenComplete = whenComplete;
        this.visitedCount = 0;
    }

    public MethodVisitor visitMethod(
        final int access,
        final String name,
        final String desc,
        final String signature,
        final String[] exceptions)
    {
        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
        if (matchTargetMethod(name, desc)) {
            if (name.equals("<init>")) {
                return new AccessTriggerConstructorAdapter(mv, getTransformContext(), access, name, desc, signature, exceptions);
            } else {
                return new AccessTriggerMethodAdapter(mv, getTransformContext(), access, name, desc, signature, exceptions);
            }
        }
        return mv;
    }

    /**
     * a method visitor used to add a rule event trigger call to a method
     */

    private class AccessTriggerMethodAdapter extends RuleTriggerMethodAdapter
    {
        /**
         * flag used by subclass to avoid inserting trigger until after super constructor has been called
         */
        protected boolean latched;

        AccessTriggerMethodAdapter(MethodVisitor mv, TransformContext transformContext, int access, String name, String descriptor, String signature, String[] exceptions)
        {
            super(mv, transformContext, access, name, descriptor, signature, exceptions);
            visitedCount = 0;
            latched = false;
        }

        public void visitFieldInsn(
            final int opcode,
            final String owner,
            final String name,
            final String desc)
        {
            if (whenComplete) {
                // access the field before generating the trigger call
                super.visitFieldInsn(opcode, owner, name, desc);
            }
            if ((count == 0 ||visitedCount < count) && matchCall(opcode, owner, name, desc)) {
                // a relevant invocation occurs in the called method
                visitedCount++;
                if (!latched && (count == 0 || visitedCount == count)) {
                    injectTriggerPoint();
                }
            }
            if (!whenComplete) {
                // access the field after generating the trigger call
                super.visitFieldInsn(opcode, owner, name, desc);
            }
        }

        private boolean matchCall(int opcode, String owner, String name, String desc)
        {
            if (!fieldName.equals(name)) {
                return false;
            }

            switch (opcode) {
                case Opcodes.GETSTATIC:
                case Opcodes.GETFIELD:
                {
                    if ((flags & Location.ACCESS_READ) == 0) {
                        return false;
                    }
                }
                break;
                case Opcodes.PUTSTATIC:
                case Opcodes.PUTFIELD:
                {
                    if ((flags & Location.ACCESS_WRITE) == 0) {
                        return false;
                    }
                }
                break;
            }
            if (ownerClass != null) {
                if (!ownerClass.equals(TypeHelper.internalizeClass(owner))) {
                    // TODO check for unqualified names
                    // if the called class has no package qualification and the owner class does then we
                    // can still match if the unqualified owner name equals the called class

                    if (ownerClass.indexOf('.') >= 0) {
                        return false;
                    }

                    int ownerPackageIdx = owner.lastIndexOf('/');
                    if (ownerPackageIdx < 0) {
                        return false;
                    } else if (!owner.substring(ownerPackageIdx+1).equals(ownerClass)) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    /**
     * a method visitor used to add a rule event trigger call to a constructor -- this has to make sure
     * the super constructor has been called before allowing a trigger call to be compiled
     */

    private class AccessTriggerConstructorAdapter extends AccessTriggerMethodAdapter
    {
        AccessTriggerConstructorAdapter(MethodVisitor mv, TransformContext transformContext, int access, String name, String descriptor, String signature, String[] exceptions)
        {
            super(mv, transformContext, access, name, descriptor, signature, exceptions);
            // ensure we don't transform calls before the super constructor is called
            latched = true;
        }

        public void visitMethodInsn(
            final int opcode,
            final String owner,
            final String name,
            final String desc)
        {
            super.visitMethodInsn(opcode, owner, name, desc);
            // hmm, this probably means the super constructor has been invoked :-)
            if (latched && opcode == Opcodes.INVOKESPECIAL) {
                latched = false;
            }

        }
    }

    private String ownerClass;
    private String fieldName;
    private int flags;
    private int count;
    private boolean whenComplete;
    private int visitedCount;
}