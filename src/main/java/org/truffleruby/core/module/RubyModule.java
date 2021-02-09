/*
 * Copyright (c) 2015, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.module;

import java.util.Set;

import org.truffleruby.RubyLanguage;
import org.truffleruby.core.klass.RubyClass;
import org.truffleruby.language.RubyDynamicObject;
import org.truffleruby.language.objects.ObjectGraph;
import org.truffleruby.language.objects.ObjectGraphNode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.source.SourceSection;

@ExportLibrary(InteropLibrary.class)
public class RubyModule extends RubyDynamicObject implements ObjectGraphNode {

    public static final RubyModule[] EMPTY_ARRAY = new RubyModule[0];

    public final ModuleFields fields;

    // Same number of inline fields as DynamicObjectBasic
    @DynamicField private long primitive1;
    @DynamicField private long primitive2;
    @DynamicField private long primitive3;
    @DynamicField private Object object1;
    @DynamicField private Object object2;
    @DynamicField private Object object3;
    @DynamicField private Object object4;

    public RubyModule(
            RubyClass rubyClass,
            Shape shape,
            RubyLanguage language,
            SourceSection sourceSection,
            RubyModule lexicalParent,
            String givenBaseName) {
        super(rubyClass, shape);
        this.fields = new ModuleFields(language, sourceSection, lexicalParent, givenBaseName, this);
    }

    protected RubyModule(RubyLanguage language, Shape classShape, String constructorOnlyForClassClass) {
        super(classShape, constructorOnlyForClassClass);

        final ModuleFields fields = new ModuleFields(language, null, null, "Class", this);
        fields.setFullName("Class");
        this.fields = fields;
    }

    public String getName() {
        return fields.getName();
    }

    @TruffleBoundary
    @Override
    public String toString() {
        return fields.getName();
    }

    @Override
    public void getAdjacentObjects(Set<Object> reachable) {
        ObjectGraph.addProperty(reachable, fields);
    }

    // region SourceLocation
    @ExportMessage
    public boolean hasSourceLocation() {
        return true;
    }

    @ExportMessage
    public SourceSection getSourceLocation() {
        return fields.getSourceSection();
    }
    // endregion

}
