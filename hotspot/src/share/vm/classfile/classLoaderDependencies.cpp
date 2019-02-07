/*
 * Copyright (c) 2018, 2019, Red Hat, Inc. and/or its affiliates.
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#include "precompiled.hpp"
#include "classfile/classLoaderDependencies.hpp"
#include "classfile/javaClasses.hpp"
#include "memory/oopFactory.hpp"
#include "utilities/debug.hpp"

void ClassLoaderDependencies::record_dependency(oop from_class_loader,
                                                oop to_class_loader,
                                                TRAPS) {
  // Dependency to the Null Class Loader doesn't
  // need to be recorded because it never goes away.
  if (to_class_loader == NULL) {
    return;
  }

  // The Null Class Loader does not generate dependencies to record.
  if (from_class_loader == NULL) {
    return;
  }

  oop current_class_loader_oop = from_class_loader;
  do {
    if (current_class_loader_oop == to_class_loader) {
      return; // This class loader is in the parent list, no need to add it.
    }
    current_class_loader_oop = java_lang_ClassLoader::parent(current_class_loader_oop);
  } while (current_class_loader_oop != NULL);

  ClassLoaderDependencies::add(Handle(THREAD, from_class_loader),
                               Handle(THREAD, to_class_loader),
                               CHECK);
}

void ClassLoaderDependencies::add(Handle from_class_loader_h,
                                  Handle dependency,
                                  TRAPS) {

  objArrayOop list_head = java_lang_ClassLoader::dependencies(from_class_loader_h());

  // Check first if this dependency is already in the list.
  // Save a pointer to the last to add to under the lock.
  objArrayOop ok = list_head;
  objArrayOop last = NULL;
  while (ok != NULL) {
    last = ok;
    if (ok->obj_at(0) == dependency()) {
      // Don't need to add it
      return;
    }
    ok = (objArrayOop)ok->obj_at(1);
  }

  // Must handle over GC points
  assert (last != NULL, "dependencies should be initialized");
  objArrayHandle last_handle(THREAD, last);

  // Create a new dependency node with fields for (class_loader, next)
  objArrayOop deps = oopFactory::new_objectArray(2, CHECK);
  deps->obj_at_put(0, dependency());

  // Must handle over GC points
  objArrayHandle new_dependency(THREAD, deps);

  // Add the dependency under lock
  ClassLoaderDependencies::locked_add(objArrayHandle(THREAD, list_head),
                                      last_handle,
                                      new_dependency,
                                      THREAD);
}

void ClassLoaderDependencies::locked_add(objArrayHandle list_head,
                                         objArrayHandle last_handle,
                                         objArrayHandle new_dependency,
                                         Thread* THREAD) {

  // Have to lock and put the new dependency on the end of the dependency
  // array so the card mark for CMS sees that this dependency is new.
  // Can probably do this lock free with some effort.
  ObjectLocker ol(list_head, THREAD);

  oop loader = new_dependency->obj_at(0);

  // Since the dependencies are only added, add to the end.
  objArrayOop end = last_handle();
  objArrayOop last = NULL;
  while (end != NULL) {
    last = end;
    // check again if another thread added it to the end.
    if (end->obj_at(0) == loader) {
      // Don't need to add it
      return;
    }
    end = (objArrayOop)end->obj_at(1);
  }
  assert (last != NULL, "dependencies should be initialized");
  // fill in the first element with the oop in new_dependency.
  if (last->obj_at(0) == NULL) {
    last->obj_at_put(0, new_dependency->obj_at(0));
  } else {
    last->obj_at_put(1, new_dependency());
  }
}
