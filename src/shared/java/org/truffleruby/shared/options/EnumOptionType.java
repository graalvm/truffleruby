/*
 * Copyright (c) 2017, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.shared.options;

import java.util.Locale;

import org.graalvm.options.OptionType;

public class EnumOptionType {

    public static <T extends Enum<T>> OptionType<T> optionTypeFor(Class<T> type) {
        return new OptionType<>(type.getName(), v -> Enum.valueOf(type, v.toUpperCase(Locale.ENGLISH)));
    }

}
