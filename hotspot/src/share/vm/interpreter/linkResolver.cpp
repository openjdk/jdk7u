/*
 * Copyright (c) 1997, 2018, Oracle and/or its affiliates. All rights reserved.
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
 *
 */

#include "precompiled.hpp"
#include "classfile/systemDictionary.hpp"
#include "classfile/vmSymbols.hpp"
#include "compiler/compileBroker.hpp"
#include "gc_interface/collectedHeap.inline.hpp"
#include "interpreter/bytecode.hpp"
#include "interpreter/interpreterRuntime.hpp"
#include "interpreter/linkResolver.hpp"
#include "memory/resourceArea.hpp"
#include "memory/universe.inline.hpp"
#include "oops/instanceKlass.hpp"
#include "oops/objArrayOop.hpp"
#include "prims/methodHandles.hpp"
#include "prims/nativeLookup.hpp"
#include "runtime/compilationPolicy.hpp"
#include "runtime/fieldDescriptor.hpp"
#include "runtime/frame.inline.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/reflection.hpp"
#include "runtime/signature.hpp"
#include "runtime/vmThread.hpp"
#ifdef TARGET_OS_FAMILY_linux
# include "thread_linux.inline.hpp"
#endif
#ifdef TARGET_OS_FAMILY_solaris
# include "thread_solaris.inline.hpp"
#endif
#ifdef TARGET_OS_FAMILY_windows
# include "thread_windows.inline.hpp"
#endif
#ifdef TARGET_OS_FAMILY_aix
# include "thread_aix.inline.hpp"
#endif
#ifdef TARGET_OS_FAMILY_bsd
# include "thread_bsd.inline.hpp"
#endif

//------------------------------------------------------------------------------------------------------------------------
// Implementation of FieldAccessInfo

void FieldAccessInfo::set(KlassHandle klass, Symbol* name, int field_index, int field_offset,
BasicType field_type, AccessFlags access_flags) {
  _klass        = klass;
  _name         = name;
  _field_index  = field_index;
  _field_offset = field_offset;
  _field_type   = field_type;
  _access_flags = access_flags;
}


//------------------------------------------------------------------------------------------------------------------------
// Implementation of CallInfo


void CallInfo::set_static(KlassHandle resolved_klass, methodHandle resolved_method, TRAPS) {
  int vtable_index = methodOopDesc::nonvirtual_vtable_index;
  set_common(resolved_klass, resolved_klass, resolved_method, resolved_method, vtable_index, CHECK);
}


void CallInfo::set_interface(KlassHandle resolved_klass, KlassHandle selected_klass, methodHandle resolved_method, methodHandle selected_method, TRAPS) {
  // This is only called for interface methods. If the resolved_method
  // comes from java/lang/Object, it can be the subject of a virtual call, so
  // we should pick the vtable index from the resolved method.
  // Other than that case, there is no valid vtable index to specify.
  int vtable_index = methodOopDesc::invalid_vtable_index;
  if (resolved_method->method_holder() == SystemDictionary::Object_klass()) {
    assert(resolved_method->vtable_index() == selected_method->vtable_index(), "sanity check");
    vtable_index = resolved_method->vtable_index();
  }
  set_common(resolved_klass, selected_klass, resolved_method, selected_method, vtable_index, CHECK);
}

void CallInfo::set_virtual(KlassHandle resolved_klass, KlassHandle selected_klass, methodHandle resolved_method, methodHandle selected_method, int vtable_index, TRAPS) {
  assert(vtable_index >= 0 || vtable_index == methodOopDesc::nonvirtual_vtable_index, "valid index");
  set_common(resolved_klass, selected_klass, resolved_method, selected_method, vtable_index, CHECK);
  assert(!resolved_method->is_compiled_lambda_form(), "these must be handled via an invokehandle call");
}

void CallInfo::set_handle(methodHandle resolved_method, Handle resolved_appendix, Handle resolved_method_type, TRAPS) {
  if (resolved_method.is_null()) {
    THROW_MSG(vmSymbols::java_lang_InternalError(), "resolved method is null");
  }
  KlassHandle resolved_klass = SystemDictionaryHandles::MethodHandle_klass();
  assert(resolved_method->intrinsic_id() == vmIntrinsics::_invokeBasic ||
         resolved_method->is_compiled_lambda_form(),
         "linkMethod must return one of these");
  int vtable_index = methodOopDesc::nonvirtual_vtable_index;
  assert(resolved_method->vtable_index() == vtable_index, "");
  set_common(resolved_klass, resolved_klass, resolved_method, resolved_method, vtable_index, CHECK);
  _resolved_appendix    = resolved_appendix;
  _resolved_method_type = resolved_method_type;
}

void CallInfo::set_common(KlassHandle resolved_klass, KlassHandle selected_klass, methodHandle resolved_method, methodHandle selected_method, int vtable_index, TRAPS) {
  assert(resolved_method->signature() == selected_method->signature(), "signatures must correspond");
  _resolved_klass  = resolved_klass;
  _selected_klass  = selected_klass;
  _resolved_method = resolved_method;
  _selected_method = selected_method;
  _vtable_index    = vtable_index;
  _resolved_appendix = Handle();
  if (CompilationPolicy::must_be_compiled(selected_method)) {
    // This path is unusual, mostly used by the '-Xcomp' stress test mode.

    // Note: with several active threads, the must_be_compiled may be true
    //       while can_be_compiled is false; remove assert
    // assert(CompilationPolicy::can_be_compiled(selected_method), "cannot compile");
    if (THREAD->is_Compiler_thread()) {
      // don't force compilation, resolve was on behalf of compiler
      return;
    }
    if (instanceKlass::cast(selected_method->method_holder())->is_not_initialized()) {
      // 'is_not_initialized' means not only '!is_initialized', but also that
      // initialization has not been started yet ('!being_initialized')
      // Do not force compilation of methods in uninitialized classes.
      // Note that doing this would throw an assert later,
      // in CompileBroker::compile_method.
      // We sometimes use the link resolver to do reflective lookups
      // even before classes are initialized.
      return;
    }
    CompileBroker::compile_method(selected_method, InvocationEntryBci,
                                  CompilationPolicy::policy()->initial_compile_level(),
                                  methodHandle(), 0, "must_be_compiled", CHECK);
  }
}


//------------------------------------------------------------------------------------------------------------------------
// Klass resolution

void LinkResolver::check_klass_accessability(KlassHandle ref_klass, KlassHandle sel_klass, TRAPS) {
  if (!Reflection::verify_class_access(ref_klass->as_klassOop(),
                                       sel_klass->as_klassOop(),
                                       true)) {
    ResourceMark rm(THREAD);
    Exceptions::fthrow(
      THREAD_AND_LOCATION,
      vmSymbols::java_lang_IllegalAccessError(),
      "tried to access class %s from class %s",
      sel_klass->external_name(),
      ref_klass->external_name()
    );
    return;
  }
}

void LinkResolver::resolve_klass(KlassHandle& result, constantPoolHandle pool, int index, TRAPS) {
  klassOop result_oop = pool->klass_ref_at(index, CHECK);
  result = KlassHandle(THREAD, result_oop);
}

void LinkResolver::resolve_klass_no_update(KlassHandle& result, constantPoolHandle pool, int index, TRAPS) {
  klassOop result_oop =
         constantPoolOopDesc::klass_ref_at_if_loaded_check(pool, index, CHECK);
  result = KlassHandle(THREAD, result_oop);
}


//------------------------------------------------------------------------------------------------------------------------
// Method resolution
//
// According to JVM spec. $5.4.3c & $5.4.3d

// Look up method in klasses, including static methods
void LinkResolver::lookup_method_in_klasses(methodHandle& result, KlassHandle klass, Symbol* name, Symbol* signature, TRAPS) {
  methodOop result_oop = klass->uncached_lookup_method(name, signature);

  //JDK 7 does not support default methods, but this code ported from JDK8 to keep code consistent for all JDK.
  if (klass->oop_is_array()) {
    // Only consider klass and super klass for arrays
    result = methodHandle(THREAD, result_oop);
    return;
  }

  if (EnableInvokeDynamic && result_oop != NULL) {
    vmIntrinsics::ID iid = result_oop->intrinsic_id();
    if (MethodHandles::is_signature_polymorphic(iid)) {
      // Do not link directly to these.  The VM must produce a synthetic one using lookup_polymorphic_method.
      return;
    }
  }
  result = methodHandle(THREAD, result_oop);
}

// returns first instance method
void LinkResolver::lookup_instance_method_in_klasses(methodHandle& result, KlassHandle klass, Symbol* name, Symbol* signature, TRAPS) {
  methodOop result_oop = klass->uncached_lookup_method(name, signature);
  result = methodHandle(THREAD, result_oop);
  while (!result.is_null() && result->is_static()) {
    KlassHandle super_klass = KlassHandle(THREAD, Klass::cast(result->method_holder())->super());
    result = methodHandle(THREAD, super_klass->uncached_lookup_method(name, signature));
  }
}


int LinkResolver::vtable_index_of_miranda_method(KlassHandle klass, Symbol* name, Symbol* signature, TRAPS) {
  ResourceMark rm(THREAD);
  klassVtable *vt = instanceKlass::cast(klass())->vtable();
  return vt->index_of_miranda(name, signature);
}

void LinkResolver::lookup_method_in_interfaces(methodHandle& result, KlassHandle klass, Symbol* name, Symbol* signature, TRAPS) {
  instanceKlass *ik = instanceKlass::cast(klass());
  result = methodHandle(THREAD, ik->lookup_method_in_all_interfaces(name, signature));
}

void LinkResolver::lookup_polymorphic_method(methodHandle& result,
                                             KlassHandle klass, Symbol* name, Symbol* full_signature,
                                             KlassHandle current_klass,
                                             Handle *appendix_result_or_null,
                                             Handle *method_type_result,
                                             TRAPS) {
  vmIntrinsics::ID iid = MethodHandles::signature_polymorphic_name_id(name);
  if (TraceMethodHandles) {
    ResourceMark rm(THREAD);
    tty->print_cr("lookup_polymorphic_method iid=%s %s.%s%s",
                  vmIntrinsics::name_at(iid), klass->external_name(),
                  name->as_C_string(), full_signature->as_C_string());
  }
  if (EnableInvokeDynamic &&
      klass() == SystemDictionary::MethodHandle_klass() &&
      iid != vmIntrinsics::_none) {
    if (MethodHandles::is_signature_polymorphic_intrinsic(iid)) {
      // Most of these do not need an up-call to Java to resolve, so can be done anywhere.
      // Do not erase last argument type (MemberName) if it is a static linkTo method.
      bool keep_last_arg = MethodHandles::is_signature_polymorphic_static(iid);
      TempNewSymbol basic_signature =
        MethodHandles::lookup_basic_type_signature(full_signature, keep_last_arg, CHECK);
      if (TraceMethodHandles) {
        ResourceMark rm(THREAD);
        tty->print_cr("lookup_polymorphic_method %s %s => basic %s",
                      name->as_C_string(),
                      full_signature->as_C_string(),
                      basic_signature->as_C_string());
      }
      result = SystemDictionary::find_method_handle_intrinsic(iid,
                                                              basic_signature,
                                                              CHECK);
      if (result.not_null()) {
        assert(result->is_method_handle_intrinsic(), "MH.invokeBasic or MH.linkTo* intrinsic");
        assert(result->intrinsic_id() != vmIntrinsics::_invokeGeneric, "wrong place to find this");
        assert(basic_signature == result->signature(), "predict the result signature");
        if (TraceMethodHandles) {
          tty->print("lookup_polymorphic_method => intrinsic ");
          result->print_on(tty);
        }
        return;
      }
    } else if (iid == vmIntrinsics::_invokeGeneric
               && !THREAD->is_Compiler_thread()
               && appendix_result_or_null != NULL) {
      // This is a method with type-checking semantics.
      // We will ask Java code to spin an adapter method for it.
      if (!MethodHandles::enabled()) {
        // Make sure the Java part of the runtime has been booted up.
        klassOop natives = SystemDictionary::MethodHandleNatives_klass();
        if (natives == NULL || instanceKlass::cast(natives)->is_not_initialized()) {
          SystemDictionary::resolve_or_fail(vmSymbols::java_lang_invoke_MethodHandleNatives(),
                                            Handle(),
                                            Handle(),
                                            true,
                                            CHECK);
        }
      }

      Handle appendix;
      Handle method_type;
      result = SystemDictionary::find_method_handle_invoker(name,
                                                            full_signature,
                                                            current_klass,
                                                            &appendix,
                                                            &method_type,
                                                            CHECK);
      if (TraceMethodHandles) {
        tty->print("lookup_polymorphic_method => (via Java) ");
        result->print_on(tty);
        tty->print("  lookup_polymorphic_method => appendix = ");
        if (appendix.is_null())  tty->print_cr("(none)");
        else                     appendix->print_on(tty);
      }
      if (result.not_null()) {
#ifdef ASSERT
        ResourceMark rm(THREAD);

        TempNewSymbol basic_signature =
          MethodHandles::lookup_basic_type_signature(full_signature, CHECK);
        int actual_size_of_params = result->size_of_parameters();
        int expected_size_of_params = ArgumentSizeComputer(basic_signature).size();
        // +1 for MethodHandle.this, +1 for trailing MethodType
        if (!MethodHandles::is_signature_polymorphic_static(iid))  expected_size_of_params += 1;
        if (appendix.not_null())                                   expected_size_of_params += 1;
        if (actual_size_of_params != expected_size_of_params) {
          tty->print_cr("*** basic_signature=%s", basic_signature->as_C_string());
          tty->print_cr("*** result for %s: ", vmIntrinsics::name_at(iid));
          result->print();
        }
        assert(actual_size_of_params == expected_size_of_params,
               err_msg("%d != %d", actual_size_of_params, expected_size_of_params));
#endif //ASSERT

        assert(appendix_result_or_null != NULL, "");
        (*appendix_result_or_null) = appendix;
        (*method_type_result)      = method_type;
        return;
      }
    }
  }
}

void LinkResolver::check_method_accessability(KlassHandle ref_klass,
                                              KlassHandle resolved_klass,
                                              KlassHandle sel_klass,
                                              methodHandle sel_method,
                                              TRAPS) {

  AccessFlags flags = sel_method->access_flags();

  // Special case:  arrays always override "clone". JVMS 2.15.
  // If the resolved klass is an array class, and the declaring class
  // is java.lang.Object and the method is "clone", set the flags
  // to public.
  //
  // We'll check for the method name first, as that's most likely
  // to be false (so we'll short-circuit out of these tests).
  if (sel_method->name() == vmSymbols::clone_name() &&
      sel_klass() == SystemDictionary::Object_klass() &&
      resolved_klass->oop_is_array()) {
    // We need to change "protected" to "public".
    assert(flags.is_protected(), "clone not protected?");
    jint new_flags = flags.as_int();
    new_flags = new_flags & (~JVM_ACC_PROTECTED);
    new_flags = new_flags | JVM_ACC_PUBLIC;
    flags.set_flags(new_flags);
  }
//  assert(extra_arg_result_or_null != NULL, "must be able to return extra argument");

  if (!Reflection::verify_field_access(ref_klass->as_klassOop(),
                                       resolved_klass->as_klassOop(),
                                       sel_klass->as_klassOop(),
                                       flags,
                                       true)) {
    ResourceMark rm(THREAD);
    Exceptions::fthrow(
      THREAD_AND_LOCATION,
      vmSymbols::java_lang_IllegalAccessError(),
      "tried to access method %s.%s%s from class %s",
      sel_klass->external_name(),
      sel_method->name()->as_C_string(),
      sel_method->signature()->as_C_string(),
      ref_klass->external_name()
    );
    return;
  }
}

void LinkResolver::resolve_method_statically(methodHandle& resolved_method, KlassHandle& resolved_klass,
                                             Bytecodes::Code code, constantPoolHandle pool, int index, TRAPS) {

  // resolve klass
  if (code == Bytecodes::_invokedynamic) {
    resolved_klass = SystemDictionaryHandles::MethodHandle_klass();
    Symbol* method_name = vmSymbols::invoke_name();
    Symbol* method_signature = pool->signature_ref_at(index);
    KlassHandle  current_klass(THREAD, pool->pool_holder());
    resolve_method(resolved_method, resolved_klass, method_name, method_signature, current_klass, true, CHECK);
    return;
  }

  resolve_klass(resolved_klass, pool, index, CHECK);

  Symbol*  method_name       = pool->name_ref_at(index);
  Symbol*  method_signature  = pool->signature_ref_at(index);
  KlassHandle  current_klass(THREAD, pool->pool_holder());

  if (pool->has_preresolution()
      || (resolved_klass() == SystemDictionary::MethodHandle_klass() &&
          MethodHandles::is_signature_polymorphic_name(resolved_klass(), method_name))) {
    methodOop result_oop = constantPoolOopDesc::method_at_if_loaded(pool, index);
    if (result_oop != NULL) {
      resolved_method = methodHandle(THREAD, result_oop);
      return;
    }
  }

  if (code == Bytecodes::_invokeinterface) {
    resolve_interface_method(resolved_method, resolved_klass, method_name, method_signature, current_klass, true, CHECK);
  } else {
    resolve_method(resolved_method, resolved_klass, method_name, method_signature, current_klass, true, CHECK);
  }
}

void LinkResolver::check_method_loader_constraints(methodHandle& resolved_method,
                                                   KlassHandle resolved_klass,
                                                   Symbol* method_name,
                                                   Symbol* method_signature,
                                                   KlassHandle current_klass,
                                                   const char* method_type, TRAPS) {
  Handle loader (THREAD, instanceKlass::cast(current_klass())->class_loader());
  Handle class_loader (THREAD, instanceKlass::cast(resolved_method->method_holder())->class_loader());
  {
    ResourceMark rm(THREAD);
    char* failed_type_name =
      SystemDictionary::check_signature_loaders(method_signature, loader,
                                                class_loader, true, CHECK);
    if (failed_type_name != NULL) {
      const char* msg = "loader constraint violation: when resolving %s"
        " \"%s\" the class loader (instance of %s) of the current class, %s,"
        " and the class loader (instance of %s) for the method's defining class, %s, have"
        " different Class objects for the type %s used in the signature";
      char* sig = methodOopDesc::name_and_sig_as_C_string(Klass::cast(resolved_klass()), method_name, method_signature);
      const char* loader1 = SystemDictionary::loader_name(loader());
      char* current = instanceKlass::cast(current_klass())->name()->as_C_string();
      const char* loader2 = SystemDictionary::loader_name(class_loader());
      char* resolved = instanceKlass::cast(resolved_klass())->name()->as_C_string();
      size_t buflen = strlen(msg) + strlen(sig) + strlen(loader1) +
        strlen(current) + strlen(loader2) + strlen(resolved) +
        strlen(failed_type_name) + strlen(method_type) + 1;
      char* buf = NEW_RESOURCE_ARRAY_IN_THREAD(THREAD, char, buflen);
      jio_snprintf(buf, buflen, msg, method_type, sig, loader1, current, loader2,
                   resolved, failed_type_name);
      THROW_MSG(vmSymbols::java_lang_LinkageError(), buf);
    }
  }
}

void LinkResolver::resolve_method(methodHandle& resolved_method, KlassHandle resolved_klass,
                                  Symbol* method_name, Symbol* method_signature,
                                  KlassHandle current_klass, bool check_access, TRAPS) {

  // 1. check if klass is not interface
  if (resolved_klass->is_interface()) {
    ResourceMark rm(THREAD);
    char buf[200];
    jio_snprintf(buf, sizeof(buf), "Found interface %s, but class was expected", Klass::cast(resolved_klass())->external_name());
    THROW_MSG(vmSymbols::java_lang_IncompatibleClassChangeError(), buf);
  }

  Handle nested_exception;

  // 2. lookup method in resolved klass and its super klasses
  lookup_method_in_klasses(resolved_method, resolved_klass, method_name, method_signature, CHECK);

  if (resolved_method.is_null() && !resolved_klass->oop_is_array()) { // not found in the class hierarchy
    // 3. lookup method in all the interfaces implemented by the resolved klass
    lookup_method_in_interfaces(resolved_method, resolved_klass, method_name, method_signature, CHECK);

    if (resolved_method.is_null()) {
      // JSR 292:  see if this is an implicitly generated method MethodHandle.linkToVirtual(*...), etc
      lookup_polymorphic_method(resolved_method, resolved_klass, method_name, method_signature,
                                current_klass, (Handle*)NULL, (Handle*)NULL, THREAD);
      if (HAS_PENDING_EXCEPTION) {
        nested_exception = Handle(THREAD, PENDING_EXCEPTION);
        CLEAR_PENDING_EXCEPTION;
      }
    }
  }

  if (resolved_method.is_null()) {
    // 4. method lookup failed
    ResourceMark rm(THREAD);
    THROW_MSG_CAUSE(vmSymbols::java_lang_NoSuchMethodError(),
                    methodOopDesc::name_and_sig_as_C_string(Klass::cast(resolved_klass()),
                                                            method_name,
                                                            method_signature),
                    nested_exception);
  }

  // 5. check if method is concrete
  if (resolved_method->is_abstract() && !resolved_klass->is_abstract()) {
    ResourceMark rm(THREAD);
    THROW_MSG(vmSymbols::java_lang_AbstractMethodError(),
              methodOopDesc::name_and_sig_as_C_string(Klass::cast(resolved_klass()),
                                                      method_name,
                                                      method_signature));
  }

  // 6. access checks, access checking may be turned off when calling from within the VM.
  if (check_access) {
    assert(current_klass.not_null() , "current_klass should not be null");

    // check if method can be accessed by the referring class
    check_method_accessability(current_klass,
                               resolved_klass,
                               KlassHandle(THREAD, resolved_method->method_holder()),
                               resolved_method,
                               CHECK);

    // check loader constraints
    check_method_loader_constraints(resolved_method, resolved_klass, method_name,
                                    method_signature, current_klass, "method", CHECK);
  }
}

void LinkResolver::resolve_interface_method(methodHandle& resolved_method,
                                            KlassHandle resolved_klass,
                                            Symbol* method_name,
                                            Symbol* method_signature,
                                            KlassHandle current_klass,
                                            bool check_access, TRAPS) {

 // check if klass is interface
  if (!resolved_klass->is_interface()) {
    ResourceMark rm(THREAD);
    char buf[200];
    jio_snprintf(buf, sizeof(buf), "Found class %s, but interface was expected", Klass::cast(resolved_klass())->external_name());
    THROW_MSG(vmSymbols::java_lang_IncompatibleClassChangeError(), buf);
  }

  // lookup method in this interface or its super, java.lang.Object
  lookup_instance_method_in_klasses(resolved_method, resolved_klass, method_name, method_signature, CHECK);

  if (resolved_method.is_null() && !resolved_klass->oop_is_array()) {
    // lookup method in all the super-interfaces
    lookup_method_in_interfaces(resolved_method, resolved_klass, method_name, method_signature, CHECK);
  }

  if (resolved_method.is_null()) {
    // no method found
    ResourceMark rm(THREAD);
    THROW_MSG(vmSymbols::java_lang_NoSuchMethodError(),
              methodOopDesc::name_and_sig_as_C_string(Klass::cast(resolved_klass()),
                                                      method_name,
                                                      method_signature));
  }

  if (check_access) {
    check_method_loader_constraints(resolved_method, resolved_klass, method_name,
                                    method_signature, current_klass, "interface method", CHECK);
  }
}

//------------------------------------------------------------------------------------------------------------------------
// Field resolution

void LinkResolver::check_field_accessability(KlassHandle ref_klass,
                                             KlassHandle resolved_klass,
                                             KlassHandle sel_klass,
                                             fieldDescriptor& fd,
                                             TRAPS) {
  if (!Reflection::verify_field_access(ref_klass->as_klassOop(),
                                       resolved_klass->as_klassOop(),
                                       sel_klass->as_klassOop(),
                                       fd.access_flags(),
                                       true)) {
    ResourceMark rm(THREAD);
    Exceptions::fthrow(
      THREAD_AND_LOCATION,
      vmSymbols::java_lang_IllegalAccessError(),
      "tried to access field %s.%s from class %s",
      sel_klass->external_name(),
      fd.name()->as_C_string(),
      ref_klass->external_name()
    );
    return;
  }
}

void LinkResolver::check_field_loader_constraints(KlassHandle ref_klass,
                                                  KlassHandle sel_klass,
                                                  Symbol* name,
                                                  Symbol* sig, TRAPS) {
  HandleMark hm(THREAD);
  Handle ref_loader (THREAD, instanceKlass::cast(ref_klass())->class_loader());
  Handle sel_loader (THREAD, instanceKlass::cast(sel_klass())->class_loader());
  {
    ResourceMark rm(THREAD);
    char* failed_type_name =
      SystemDictionary::check_signature_loaders(sig, ref_loader, sel_loader,
                                                false, CHECK);
    if (failed_type_name != NULL) {
      const char* msg = "loader constraint violation: when resolving field"
        " \"%s\" the class loader (instance of %s) of the referring class, "
        "%s, and the class loader (instance of %s) for the field's resolved "
        "type, %s, have different Class objects for that type";
      char* field_name = name->as_C_string();
      const char* loader1 = SystemDictionary::loader_name(ref_loader());
      char* sel = instanceKlass::cast(sel_klass())->name()->as_C_string();
      const char* loader2 = SystemDictionary::loader_name(sel_loader());
      size_t buflen = strlen(msg) + strlen(field_name) + strlen(loader1) +
        strlen(sel) + strlen(loader2) + strlen(failed_type_name);
      char* buf = NEW_RESOURCE_ARRAY_IN_THREAD(THREAD, char, buflen);
      jio_snprintf(buf, buflen, msg, field_name, loader1, sel, loader2,
                   failed_type_name);
      THROW_MSG(vmSymbols::java_lang_LinkageError(), buf);
    }
  }
}

void LinkResolver::resolve_field(FieldAccessInfo& result, constantPoolHandle pool, int index, Bytecodes::Code byte, bool check_only, TRAPS) {
  resolve_field(result, pool, index, byte, check_only, true, CHECK);
}

void LinkResolver::resolve_field(FieldAccessInfo& result, constantPoolHandle pool, int index, Bytecodes::Code byte, bool check_only, bool update_pool, TRAPS) {
  assert(byte == Bytecodes::_getstatic || byte == Bytecodes::_putstatic ||
         byte == Bytecodes::_getfield  || byte == Bytecodes::_putfield, "bad bytecode");

  bool is_static = (byte == Bytecodes::_getstatic || byte == Bytecodes::_putstatic);
  bool is_put    = (byte == Bytecodes::_putfield  || byte == Bytecodes::_putstatic);

  // resolve specified klass
  KlassHandle resolved_klass;
  if (update_pool) {
    resolve_klass(resolved_klass, pool, index, CHECK);
  } else {
    resolve_klass_no_update(resolved_klass, pool, index, CHECK);
  }
  // Load these early in case the resolve of the containing klass fails
  Symbol* field = pool->name_ref_at(index);
  Symbol* sig   = pool->signature_ref_at(index);
  // Check if there's a resolved klass containing the field
  if( resolved_klass.is_null() ) {
    ResourceMark rm(THREAD);
    THROW_MSG(vmSymbols::java_lang_NoSuchFieldError(), field->as_C_string());
  }

  // Resolve instance field
  fieldDescriptor fd; // find_field initializes fd if found
  KlassHandle sel_klass(THREAD, resolved_klass->find_field(field, sig, &fd));
  // check if field exists; i.e., if a klass containing the field def has been selected
  if (sel_klass.is_null()){
    ResourceMark rm(THREAD);
    THROW_MSG(vmSymbols::java_lang_NoSuchFieldError(), field->as_C_string());
  }

  // check access
  KlassHandle ref_klass(THREAD, pool->pool_holder());
  check_field_accessability(ref_klass, resolved_klass, sel_klass, fd, CHECK);

  // check for errors
  if (is_static != fd.is_static()) {
    ResourceMark rm(THREAD);
    char msg[200];
    jio_snprintf(msg, sizeof(msg), "Expected %s field %s.%s", is_static ? "static" : "non-static", Klass::cast(resolved_klass())->external_name(), fd.name()->as_C_string());
    THROW_MSG(vmSymbols::java_lang_IncompatibleClassChangeError(), msg);
  }

  // Final fields can only be accessed from its own class.
  if (is_put && fd.access_flags().is_final() && sel_klass() != pool->pool_holder()) {
    THROW(vmSymbols::java_lang_IllegalAccessError());
  }

  // initialize resolved_klass if necessary
  // note 1: the klass which declared the field must be initialized (i.e, sel_klass)
  //         according to the newest JVM spec (5.5, p.170) - was bug (gri 7/28/99)
  //
  // note 2: we don't want to force initialization if we are just checking
  //         if the field access is legal; e.g., during compilation
  if (is_static && !check_only) {
    sel_klass->initialize(CHECK);
  }
  LinkResolver::check_field_loader_constraints(ref_klass, sel_klass, field, sig, CHECK);

  // return information. note that the klass is set to the actual klass containing the
  // field, otherwise access of static fields in superclasses will not work.
  KlassHandle holder (THREAD, fd.field_holder());
  Symbol*  name   = fd.name();
  result.set(holder, name, fd.index(), fd.offset(), fd.field_type(), fd.access_flags());
}


//------------------------------------------------------------------------------------------------------------------------
// Invoke resolution
//
// Naming conventions:
//
// resolved_method    the specified method (i.e., static receiver specified via constant pool index)
// sel_method         the selected method  (selected via run-time lookup; e.g., based on dynamic receiver class)
// resolved_klass     the specified klass  (i.e., specified via constant pool index)
// recv_klass         the receiver klass


void LinkResolver::resolve_static_call(CallInfo& result, KlassHandle& resolved_klass, Symbol* method_name,
                                       Symbol* method_signature, KlassHandle current_klass,
                                       bool check_access, bool initialize_class, TRAPS) {
  methodHandle resolved_method;
  linktime_resolve_static_method(resolved_method, resolved_klass, method_name, method_signature, current_klass, check_access, CHECK);
  resolved_klass = KlassHandle(THREAD, Klass::cast(resolved_method->method_holder()));

  // Initialize klass (this should only happen if everything is ok)
  if (initialize_class && resolved_klass->should_be_initialized()) {
    resolved_klass->initialize(CHECK);
    linktime_resolve_static_method(resolved_method, resolved_klass, method_name, method_signature, current_klass, check_access, CHECK);
  }

  // setup result
  result.set_static(resolved_klass, resolved_method, CHECK);
}

// throws linktime exceptions
void LinkResolver::linktime_resolve_static_method(methodHandle& resolved_method, KlassHandle resolved_klass,
                                                  Symbol* method_name, Symbol* method_signature,
                                                  KlassHandle current_klass, bool check_access, TRAPS) {

  resolve_method(resolved_method, resolved_klass, method_name, method_signature, current_klass, check_access, CHECK);
  assert(resolved_method->name() != vmSymbols::class_initializer_name(), "should have been checked in verifier");

  // check if static
  if (!resolved_method->is_static()) {
    ResourceMark rm(THREAD);
    char buf[200];
    jio_snprintf(buf, sizeof(buf), "Expected static method %s", methodOopDesc::name_and_sig_as_C_string(Klass::cast(resolved_klass()),
                                                      resolved_method->name(),
                                                      resolved_method->signature()));
    THROW_MSG(vmSymbols::java_lang_IncompatibleClassChangeError(), buf);
  }
}


void LinkResolver::resolve_special_call(CallInfo& result, KlassHandle resolved_klass, Symbol* method_name,
                                        Symbol* method_signature, KlassHandle current_klass, bool check_access, TRAPS) {
  methodHandle resolved_method;
  linktime_resolve_special_method(resolved_method, resolved_klass, method_name, method_signature, current_klass, check_access, CHECK);
  runtime_resolve_special_method(result, resolved_method, resolved_klass, current_klass, check_access, CHECK);
}

// throws linktime exceptions
void LinkResolver::linktime_resolve_special_method(methodHandle& resolved_method, KlassHandle resolved_klass,
                                                   Symbol* method_name, Symbol* method_signature,
                                                   KlassHandle current_klass, bool check_access, TRAPS) {

  resolve_method(resolved_method, resolved_klass, method_name, method_signature, current_klass, check_access, CHECK);

  // check if method name is <init>, that it is found in same klass as static type
  if (resolved_method->name() == vmSymbols::object_initializer_name() &&
      resolved_method->method_holder() != resolved_klass()) {
    ResourceMark rm(THREAD);
    Exceptions::fthrow(
      THREAD_AND_LOCATION,
      vmSymbols::java_lang_NoSuchMethodError(),
      "%s: method %s%s not found",
      resolved_klass->external_name(),
      resolved_method->name()->as_C_string(),
      resolved_method->signature()->as_C_string()
    );
    return;
  }

  // check if not static
  if (resolved_method->is_static()) {
    ResourceMark rm(THREAD);
    char buf[200];
    jio_snprintf(buf, sizeof(buf),
                 "Expecting non-static method %s",
                 methodOopDesc::name_and_sig_as_C_string(Klass::cast(resolved_klass()),
                                                         resolved_method->name(),
                                                         resolved_method->signature()));
    THROW_MSG(vmSymbols::java_lang_IncompatibleClassChangeError(), buf);
  }
}

// throws runtime exceptions
void LinkResolver::runtime_resolve_special_method(CallInfo& result, methodHandle resolved_method, KlassHandle resolved_klass,
                                                  KlassHandle current_klass, bool check_access, TRAPS) {

  // resolved method is selected method unless we have an old-style lookup
  methodHandle sel_method(THREAD, resolved_method());

  // check if this is an old-style super call and do a new lookup if so
  { KlassHandle method_klass  = KlassHandle(THREAD,
                                            resolved_method->method_holder());

    if (check_access &&
        // a) check if ACC_SUPER flag is set for the current class
        (current_klass->is_super() || !AllowNonVirtualCalls) &&
        // b) check if the method class is a superclass of the current class (superclass relation is not reflexive!)
        current_klass->is_subtype_of(method_klass()) && current_klass() != method_klass() &&
        // c) check if the method is not <init>
        resolved_method->name() != vmSymbols::object_initializer_name()) {
      // Lookup super method
      KlassHandle super_klass(THREAD, current_klass->super());
      lookup_instance_method_in_klasses(sel_method, super_klass,
                           resolved_method->name(),
                           resolved_method->signature(), CHECK);
      // check if found
      if (sel_method.is_null()) {
        ResourceMark rm(THREAD);
        THROW_MSG(vmSymbols::java_lang_AbstractMethodError(),
                  methodOopDesc::name_and_sig_as_C_string(Klass::cast(resolved_klass()),
                                            resolved_method->name(),
                                            resolved_method->signature()));
      } else if (sel_method() != resolved_method()) {
        check_method_loader_constraints(sel_method, resolved_klass,
                                        sel_method->name(), sel_method->signature(),
                                        current_klass, "method", CHECK);
      }
    }
  }

  // check if not static
  if (sel_method->is_static()) {
    ResourceMark rm(THREAD);
    char buf[200];
    jio_snprintf(buf, sizeof(buf), "Expecting non-static method %s", methodOopDesc::name_and_sig_as_C_string(Klass::cast(resolved_klass()),
                                                                                                             resolved_method->name(),
                                                                                                             resolved_method->signature()));
    THROW_MSG(vmSymbols::java_lang_IncompatibleClassChangeError(), buf);
  }

  // check if abstract
  if (sel_method->is_abstract()) {
    ResourceMark rm(THREAD);
    THROW_MSG(vmSymbols::java_lang_AbstractMethodError(),
              methodOopDesc::name_and_sig_as_C_string(Klass::cast(resolved_klass()),
                                                      sel_method->name(),
                                                      sel_method->signature()));
  }

  // setup result
  result.set_static(resolved_klass, sel_method, CHECK);
}

void LinkResolver::resolve_virtual_call(CallInfo& result, Handle recv, KlassHandle receiver_klass, KlassHandle resolved_klass,
                                        Symbol* method_name, Symbol* method_signature, KlassHandle current_klass,
                                        bool check_access, bool check_null_and_abstract, TRAPS) {
  methodHandle resolved_method;
  linktime_resolve_virtual_method(resolved_method, resolved_klass, method_name, method_signature, current_klass, check_access, CHECK);
  runtime_resolve_virtual_method(result, resolved_method, resolved_klass, recv, receiver_klass, check_null_and_abstract, CHECK);
}

// throws linktime exceptions
void LinkResolver::linktime_resolve_virtual_method(methodHandle &resolved_method, KlassHandle resolved_klass,
                                                   Symbol* method_name, Symbol* method_signature,
                                                   KlassHandle current_klass, bool check_access, TRAPS) {
  // normal method resolution
  resolve_method(resolved_method, resolved_klass, method_name, method_signature, current_klass, check_access, CHECK);

  assert(resolved_method->name() != vmSymbols::object_initializer_name(), "should have been checked in verifier");
  assert(resolved_method->name() != vmSymbols::class_initializer_name (), "should have been checked in verifier");

  // check if not static
  if (resolved_method->is_static()) {
    ResourceMark rm(THREAD);
    char buf[200];
    jio_snprintf(buf, sizeof(buf), "Expecting non-static method %s", methodOopDesc::name_and_sig_as_C_string(Klass::cast(resolved_klass()),
                                                                                                             resolved_method->name(),
                                                                                                             resolved_method->signature()));
    THROW_MSG(vmSymbols::java_lang_IncompatibleClassChangeError(), buf);
  }
}

// throws runtime exceptions
void LinkResolver::runtime_resolve_virtual_method(CallInfo& result,
                                                  methodHandle resolved_method,
                                                  KlassHandle resolved_klass,
                                                  Handle recv,
                                                  KlassHandle recv_klass,
                                                  bool check_null_and_abstract,
                                                  TRAPS) {

  // setup default return values
  int vtable_index = methodOopDesc::invalid_vtable_index;
  methodHandle selected_method;

  assert(recv.is_null() || recv->is_oop(), "receiver is not an oop");

  // runtime method resolution
  if (check_null_and_abstract && recv.is_null()) { // check if receiver exists
    THROW(vmSymbols::java_lang_NullPointerException());
  }

  // Virtual methods cannot be resolved before its klass has been linked, for otherwise the methodOop's
  // has not been rewritten, and the vtable initialized.
  assert(instanceKlass::cast(resolved_method->method_holder())->is_linked(), "must be linked");

  // Virtual methods cannot be resolved before its klass has been linked, for otherwise the methodOop's
  // has not been rewritten, and the vtable initialized. Make sure to do this after the nullcheck, since
  // a missing receiver might result in a bogus lookup.
  assert(instanceKlass::cast(resolved_method->method_holder())->is_linked(), "must be linked");

  // do lookup based on receiver klass using the vtable index
  if (resolved_method->method_holder()->klass_part()->is_interface()) { // miranda method
    vtable_index = vtable_index_of_miranda_method(resolved_klass,
                           resolved_method->name(),
                           resolved_method->signature(), CHECK);
    assert(vtable_index >= 0 , "we should have valid vtable index at this point");

    instanceKlass* inst = instanceKlass::cast(recv_klass());
    selected_method = methodHandle(THREAD, inst->method_at_vtable(vtable_index));
  } else {
    // at this point we are sure that resolved_method is virtual and not
    // a miranda method; therefore, it must have a valid vtable index.
    vtable_index = resolved_method->vtable_index();
    // We could get a negative vtable_index for final methods,
    // because as an optimization they are they are never put in the vtable,
    // unless they override an existing method.
    // If we do get a negative, it means the resolved method is the the selected
    // method, and it can never be changed by an override.
    if (vtable_index == methodOopDesc::nonvirtual_vtable_index) {
      assert(resolved_method->can_be_statically_bound(), "cannot override this method");
      selected_method = resolved_method;
    } else {
      // recv_klass might be an arrayKlassOop but all vtables start at
      // the same place. The cast is to avoid virtual call and assertion.
      instanceKlass* inst = (instanceKlass*)recv_klass()->klass_part();
      selected_method = methodHandle(THREAD, inst->method_at_vtable(vtable_index));
    }
  }

  // check if method exists
  if (selected_method.is_null()) {
    ResourceMark rm(THREAD);
    THROW_MSG(vmSymbols::java_lang_AbstractMethodError(),
              methodOopDesc::name_and_sig_as_C_string(Klass::cast(resolved_klass()),
                                                      resolved_method->name(),
                                                      resolved_method->signature()));
  }

  // check if abstract
  if (check_null_and_abstract && selected_method->is_abstract()) {
    ResourceMark rm(THREAD);
    THROW_MSG(vmSymbols::java_lang_AbstractMethodError(),
              methodOopDesc::name_and_sig_as_C_string(Klass::cast(resolved_klass()),
                                                      selected_method->name(),
                                                      selected_method->signature()));
  }

  // setup result
  result.set_virtual(resolved_klass, recv_klass, resolved_method, selected_method, vtable_index, CHECK);
}

void LinkResolver::resolve_interface_call(CallInfo& result, Handle recv, KlassHandle recv_klass, KlassHandle resolved_klass,
                                          Symbol* method_name, Symbol* method_signature, KlassHandle current_klass,
                                          bool check_access, bool check_null_and_abstract, TRAPS) {
  methodHandle resolved_method;
  linktime_resolve_interface_method(resolved_method, resolved_klass, method_name, method_signature, current_klass, check_access, CHECK);
  runtime_resolve_interface_method(result, resolved_method, resolved_klass, recv, recv_klass, check_null_and_abstract, CHECK);
}

// throws linktime exceptions
void LinkResolver::linktime_resolve_interface_method(methodHandle& resolved_method, KlassHandle resolved_klass, Symbol* method_name,
                                                     Symbol* method_signature, KlassHandle current_klass, bool check_access, TRAPS) {
  // normal interface method resolution
  resolve_interface_method(resolved_method, resolved_klass, method_name, method_signature, current_klass, check_access, CHECK);

  assert(resolved_method->name() != vmSymbols::object_initializer_name(), "should have been checked in verifier");
  assert(resolved_method->name() != vmSymbols::class_initializer_name (), "should have been checked in verifier");
}

// throws runtime exceptions
void LinkResolver::runtime_resolve_interface_method(CallInfo& result, methodHandle resolved_method, KlassHandle resolved_klass,
                                                    Handle recv, KlassHandle recv_klass, bool check_null_and_abstract, TRAPS) {
  // check if receiver exists
  if (check_null_and_abstract && recv.is_null()) {
    THROW(vmSymbols::java_lang_NullPointerException());
  }

  // check if receiver klass implements the resolved interface
  if (!recv_klass->is_subtype_of(resolved_klass())) {
    ResourceMark rm(THREAD);
    char buf[200];
    jio_snprintf(buf, sizeof(buf), "Class %s does not implement the requested interface %s",
                 (Klass::cast(recv_klass()))->external_name(),
                 (Klass::cast(resolved_klass()))->external_name());
    THROW_MSG(vmSymbols::java_lang_IncompatibleClassChangeError(), buf);
  }
  // do lookup based on receiver klass
  methodHandle sel_method;
  lookup_instance_method_in_klasses(sel_method, recv_klass,
            resolved_method->name(),
            resolved_method->signature(), CHECK);
  // check if method exists
  if (sel_method.is_null()) {
    ResourceMark rm(THREAD);
    THROW_MSG(vmSymbols::java_lang_AbstractMethodError(),
              methodOopDesc::name_and_sig_as_C_string(Klass::cast(recv_klass()),
                                                      resolved_method->name(),
                                                      resolved_method->signature()));
  }
  // check if public
  if (!sel_method->is_public()) {
    ResourceMark rm(THREAD);
    THROW_MSG(vmSymbols::java_lang_IllegalAccessError(),
              methodOopDesc::name_and_sig_as_C_string(Klass::cast(recv_klass()),
                                                      sel_method->name(),
                                                      sel_method->signature()));
  }
  // check if abstract
  if (check_null_and_abstract && sel_method->is_abstract()) {
    ResourceMark rm(THREAD);
    THROW_MSG(vmSymbols::java_lang_AbstractMethodError(),
              methodOopDesc::name_and_sig_as_C_string(Klass::cast(recv_klass()),
                                                      sel_method->name(),
                                                      sel_method->signature()));
  }
  // setup result
  result.set_interface(resolved_klass, recv_klass, resolved_method, sel_method, CHECK);
}


methodHandle LinkResolver::linktime_resolve_interface_method_or_null(
                                                 KlassHandle resolved_klass,
                                                 Symbol* method_name,
                                                 Symbol* method_signature,
                                                 KlassHandle current_klass,
                                                 bool check_access) {
  EXCEPTION_MARK;
  methodHandle method_result;
  linktime_resolve_interface_method(method_result, resolved_klass, method_name, method_signature, current_klass, check_access, THREAD);
  if (HAS_PENDING_EXCEPTION) {
    CLEAR_PENDING_EXCEPTION;
    return methodHandle();
  } else {
    return method_result;
  }
}

methodHandle LinkResolver::linktime_resolve_virtual_method_or_null(
                                                 KlassHandle resolved_klass,
                                                 Symbol* method_name,
                                                 Symbol* method_signature,
                                                 KlassHandle current_klass,
                                                 bool check_access) {
  EXCEPTION_MARK;
  methodHandle method_result;
  linktime_resolve_virtual_method(method_result, resolved_klass, method_name, method_signature, current_klass, check_access, THREAD);
  if (HAS_PENDING_EXCEPTION) {
    CLEAR_PENDING_EXCEPTION;
    return methodHandle();
  } else {
    return method_result;
  }
}

methodHandle LinkResolver::resolve_virtual_call_or_null(
                                                 KlassHandle receiver_klass,
                                                 KlassHandle resolved_klass,
                                                 Symbol* name,
                                                 Symbol* signature,
                                                 KlassHandle current_klass) {
  EXCEPTION_MARK;
  CallInfo info;
  resolve_virtual_call(info, Handle(), receiver_klass, resolved_klass, name, signature, current_klass, true, false, THREAD);
  if (HAS_PENDING_EXCEPTION) {
    CLEAR_PENDING_EXCEPTION;
    return methodHandle();
  }
  return info.selected_method();
}

methodHandle LinkResolver::resolve_interface_call_or_null(
                                                 KlassHandle receiver_klass,
                                                 KlassHandle resolved_klass,
                                                 Symbol* name,
                                                 Symbol* signature,
                                                 KlassHandle current_klass) {
  EXCEPTION_MARK;
  CallInfo info;
  resolve_interface_call(info, Handle(), receiver_klass, resolved_klass, name, signature, current_klass, true, false, THREAD);
  if (HAS_PENDING_EXCEPTION) {
    CLEAR_PENDING_EXCEPTION;
    return methodHandle();
  }
  return info.selected_method();
}

int LinkResolver::resolve_virtual_vtable_index(
                                               KlassHandle receiver_klass,
                                               KlassHandle resolved_klass,
                                               Symbol* name,
                                               Symbol* signature,
                                               KlassHandle current_klass) {
  EXCEPTION_MARK;
  CallInfo info;
  resolve_virtual_call(info, Handle(), receiver_klass, resolved_klass, name, signature, current_klass, true, false, THREAD);
  if (HAS_PENDING_EXCEPTION) {
    CLEAR_PENDING_EXCEPTION;
    return methodOopDesc::invalid_vtable_index;
  }
  return info.vtable_index();
}

methodHandle LinkResolver::resolve_static_call_or_null(
                                                  KlassHandle resolved_klass,
                                                  Symbol* name,
                                                  Symbol* signature,
                                                  KlassHandle current_klass) {
  EXCEPTION_MARK;
  CallInfo info;
  resolve_static_call(info, resolved_klass, name, signature, current_klass, true, false, THREAD);
  if (HAS_PENDING_EXCEPTION) {
    CLEAR_PENDING_EXCEPTION;
    return methodHandle();
  }
  return info.selected_method();
}

methodHandle LinkResolver::resolve_special_call_or_null(KlassHandle resolved_klass, Symbol* name, Symbol* signature,
                                                        KlassHandle current_klass) {
  EXCEPTION_MARK;
  CallInfo info;
  resolve_special_call(info, resolved_klass, name, signature, current_klass, true, THREAD);
  if (HAS_PENDING_EXCEPTION) {
    CLEAR_PENDING_EXCEPTION;
    return methodHandle();
  }
  return info.selected_method();
}



//------------------------------------------------------------------------------------------------------------------------
// ConstantPool entries

void LinkResolver::resolve_invoke(CallInfo& result, Handle recv, constantPoolHandle pool, int index, Bytecodes::Code byte, TRAPS) {
  switch (byte) {
    case Bytecodes::_invokestatic   : resolve_invokestatic   (result,       pool, index, CHECK); break;
    case Bytecodes::_invokespecial  : resolve_invokespecial  (result,       pool, index, CHECK); break;
    case Bytecodes::_invokevirtual  : resolve_invokevirtual  (result, recv, pool, index, CHECK); break;
    case Bytecodes::_invokehandle   : resolve_invokehandle   (result,       pool, index, CHECK); break;
    case Bytecodes::_invokedynamic  : resolve_invokedynamic  (result,       pool, index, CHECK); break;
    case Bytecodes::_invokeinterface: resolve_invokeinterface(result, recv, pool, index, CHECK); break;
  }
  return;
}

void LinkResolver::resolve_pool(KlassHandle& resolved_klass, Symbol*& method_name, Symbol*& method_signature,
                                KlassHandle& current_klass, constantPoolHandle pool, int index, TRAPS) {
   // resolve klass
  resolve_klass(resolved_klass, pool, index, CHECK);

  // Get name, signature, and static klass
  method_name      = pool->name_ref_at(index);
  method_signature = pool->signature_ref_at(index);
  current_klass    = KlassHandle(THREAD, pool->pool_holder());
}


void LinkResolver::resolve_invokestatic(CallInfo& result, constantPoolHandle pool, int index, TRAPS) {
  KlassHandle  resolved_klass;
  Symbol* method_name = NULL;
  Symbol* method_signature = NULL;
  KlassHandle  current_klass;
  resolve_pool(resolved_klass, method_name,  method_signature, current_klass, pool, index, CHECK);
  resolve_static_call(result, resolved_klass, method_name, method_signature, current_klass, true, true, CHECK);
}


void LinkResolver::resolve_invokespecial(CallInfo& result, constantPoolHandle pool, int index, TRAPS) {
  KlassHandle  resolved_klass;
  Symbol* method_name = NULL;
  Symbol* method_signature = NULL;
  KlassHandle  current_klass;
  resolve_pool(resolved_klass, method_name,  method_signature, current_klass, pool, index, CHECK);
  resolve_special_call(result, resolved_klass, method_name, method_signature, current_klass, true, CHECK);
}


void LinkResolver::resolve_invokevirtual(CallInfo& result, Handle recv,
                                          constantPoolHandle pool, int index,
                                          TRAPS) {

  KlassHandle  resolved_klass;
  Symbol* method_name = NULL;
  Symbol* method_signature = NULL;
  KlassHandle  current_klass;
  resolve_pool(resolved_klass, method_name,  method_signature, current_klass, pool, index, CHECK);
  KlassHandle recvrKlass (THREAD, recv.is_null() ? (klassOop)NULL : recv->klass());
  resolve_virtual_call(result, recv, recvrKlass, resolved_klass, method_name, method_signature, current_klass, true, true, CHECK);
}


void LinkResolver::resolve_invokeinterface(CallInfo& result, Handle recv, constantPoolHandle pool, int index, TRAPS) {
  KlassHandle  resolved_klass;
  Symbol* method_name = NULL;
  Symbol* method_signature = NULL;
  KlassHandle  current_klass;
  resolve_pool(resolved_klass, method_name,  method_signature, current_klass, pool, index, CHECK);
  KlassHandle recvrKlass (THREAD, recv.is_null() ? (klassOop)NULL : recv->klass());
  resolve_interface_call(result, recv, recvrKlass, resolved_klass, method_name, method_signature, current_klass, true, true, CHECK);
}


void LinkResolver::resolve_invokehandle(CallInfo& result, constantPoolHandle pool, int index, TRAPS) {
  assert(EnableInvokeDynamic, "");
  // This guy is reached from InterpreterRuntime::resolve_invokehandle.
  KlassHandle  resolved_klass;
  Symbol* method_name = NULL;
  Symbol* method_signature = NULL;
  KlassHandle  current_klass;
  resolve_pool(resolved_klass, method_name,  method_signature, current_klass, pool, index, CHECK);
  if (TraceMethodHandles) {
    ResourceMark rm(THREAD);
    tty->print_cr("resolve_invokehandle %s %s", method_name->as_C_string(), method_signature->as_C_string());
  }
  resolve_handle_call(result, resolved_klass, method_name, method_signature, current_klass, true, CHECK);
}

void LinkResolver::resolve_handle_call(CallInfo& result, KlassHandle resolved_klass,
                                       Symbol* method_name, Symbol* method_signature,
                                       KlassHandle current_klass, bool check_access,
                                       TRAPS) {
  // JSR 292:  this must be an implicitly generated method MethodHandle.invokeExact(*...) or similar
  assert(resolved_klass() == SystemDictionary::MethodHandle_klass(), "");
  assert(MethodHandles::is_signature_polymorphic_name(method_name), "");
  methodHandle resolved_method;
  Handle resolved_appendix;
  Handle resolved_method_type;
  lookup_polymorphic_method(resolved_method, resolved_klass,
                            method_name, method_signature,
                            current_klass, &resolved_appendix, &resolved_method_type, CHECK);
  if (check_access) {
    vmIntrinsics::ID iid = MethodHandles::signature_polymorphic_name_id(method_name);
    if (MethodHandles::is_signature_polymorphic_intrinsic(iid)) {
      // Check if method can be accessed by the referring class.
      // MH.linkTo* invocations are not rewritten to invokehandle.
      assert(iid == vmIntrinsics::_invokeBasic, err_msg("%s", vmIntrinsics::name_at(iid)));

      assert(current_klass.not_null(), "current_klass should not be null");
      check_method_accessability(current_klass,
                                 resolved_klass,
                                 resolved_method->method_holder(),
                                 resolved_method,
                                 CHECK);
    } else {
      // Java code is free to arbitrarily link signature-polymorphic invokers.
      assert(iid == vmIntrinsics::_invokeGeneric, err_msg("not an invoker: %s", vmIntrinsics::name_at(iid)));
      assert(MethodHandles::is_signature_polymorphic_public_name(resolved_klass(), method_name), "not public");
    }
  }
  result.set_handle(resolved_method, resolved_appendix, resolved_method_type, CHECK);
}


void LinkResolver::resolve_invokedynamic(CallInfo& result, constantPoolHandle pool, int index, TRAPS) {
  assert(EnableInvokeDynamic, "");
  pool->set_invokedynamic();    // mark header to flag active call sites

  //resolve_pool(<resolved_klass>, method_name, method_signature, current_klass, pool, index, CHECK);
  Symbol* method_name       = pool->name_ref_at(index);
  Symbol* method_signature  = pool->signature_ref_at(index);
  KlassHandle current_klass = KlassHandle(THREAD, pool->pool_holder());

  // Resolve the bootstrap specifier (BSM + optional arguments).
  Handle bootstrap_specifier;
  // Check if CallSite has been bound already:
  ConstantPoolCacheEntry* cpce = pool->cache()->secondary_entry_at(index);
  if (cpce->is_f1_null()) {
    int pool_index = pool->cache()->main_entry_at(index)->constant_pool_index();
    oop bsm_info = pool->resolve_bootstrap_specifier_at(pool_index, CHECK);
    assert(bsm_info != NULL, "");
    // FIXME: Cache this once per BootstrapMethods entry, not once per CONSTANT_InvokeDynamic.
    bootstrap_specifier = Handle(THREAD, bsm_info);
  }
  if (!cpce->is_f1_null()) {
    methodHandle method(     THREAD, cpce->f2_as_vfinal_method());
    Handle       appendix(   THREAD, cpce->appendix_if_resolved(pool));
    Handle       method_type(THREAD, cpce->method_type_if_resolved(pool));
    result.set_handle(method, appendix, method_type, CHECK);
    return;
  }

  if (TraceMethodHandles) {
    tty->print_cr("resolve_invokedynamic #%d %s %s",
                  constantPoolCacheOopDesc::decode_secondary_index(index),
                  method_name->as_C_string(), method_signature->as_C_string());
    tty->print("  BSM info: "); bootstrap_specifier->print();
  }

  resolve_dynamic_call(result, bootstrap_specifier, method_name, method_signature, current_klass, CHECK);
}

void LinkResolver::resolve_dynamic_call(CallInfo& result,
                                        Handle bootstrap_specifier,
                                        Symbol* method_name, Symbol* method_signature,
                                        KlassHandle current_klass,
                                        TRAPS) {
  // JSR 292:  this must resolve to an implicitly generated method MH.linkToCallSite(*...)
  // The appendix argument is likely to be a freshly-created CallSite.
  Handle       resolved_appendix;
  Handle       resolved_method_type;
  methodHandle resolved_method =
    SystemDictionary::find_dynamic_call_site_invoker(current_klass,
                                                     bootstrap_specifier,
                                                     method_name, method_signature,
                                                     &resolved_appendix,
                                                     &resolved_method_type,
                                                     THREAD);
  if (HAS_PENDING_EXCEPTION) {
    if (TraceMethodHandles) {
      tty->print_cr("invokedynamic throws BSME for " INTPTR_FORMAT, p2i(PENDING_EXCEPTION));
      PENDING_EXCEPTION->print();
    }
    if (PENDING_EXCEPTION->is_a(SystemDictionary::BootstrapMethodError_klass())) {
      // throw these guys, since they are already wrapped
      return;
    }
    if (!PENDING_EXCEPTION->is_a(SystemDictionary::LinkageError_klass())) {
      // intercept only LinkageErrors which might have failed to wrap
      return;
    }
    // See the "Linking Exceptions" section for the invokedynamic instruction in the JVMS.
    Handle nested_exception(THREAD, PENDING_EXCEPTION);
    CLEAR_PENDING_EXCEPTION;
    THROW_CAUSE(vmSymbols::java_lang_BootstrapMethodError(), nested_exception)
  }
  result.set_handle(resolved_method, resolved_appendix, resolved_method_type, CHECK);
}

//------------------------------------------------------------------------------------------------------------------------
#ifndef PRODUCT

void FieldAccessInfo::print() {
  ResourceMark rm;
  tty->print_cr("Field %s@%d", name()->as_C_string(), field_offset());
}

#endif
