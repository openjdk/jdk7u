/*
 * Copyright (c) 1997, 2014, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_VM_OOPS_INSTANCEKLASS_HPP
#define SHARE_VM_OOPS_INSTANCEKLASS_HPP

#include "memory/referenceType.hpp"
#include "oops/constMethodOop.hpp"
#include "oops/constantPoolOop.hpp"
#include "oops/fieldInfo.hpp"
#include "oops/instanceOop.hpp"
#include "oops/klassOop.hpp"
#include "oops/klassVtable.hpp"
#include "oops/objArrayOop.hpp"
#include "runtime/atomic.hpp"
#include "runtime/handles.hpp"
#include "runtime/os.hpp"
#include "utilities/accessFlags.hpp"
#include "utilities/bitMap.inline.hpp"
#include "trace/traceMacros.hpp"

// An instanceKlass is the VM level representation of a Java class.
// It contains all information needed for at class at execution runtime.

//  instanceKlass layout:
//    [header                     ] klassOop
//    [klass pointer              ] klassOop
//    [C++ vtbl pointer           ] Klass
//    [subtype cache              ] Klass
//    [instance size              ] Klass
//    [java mirror                ] Klass
//    [super                      ] Klass
//    [access_flags               ] Klass
//    [name                       ] Klass
//    [first subklass             ] Klass
//    [next sibling               ] Klass
//    [array klasses              ]
//    [methods                    ]
//    [local interfaces           ]
//    [transitive interfaces      ]
//    [fields                     ]
//    [constants                  ]
//    [class loader               ]
//    [protection domain          ]
//    [signers                    ]
//    [source file name           ]
//    [inner classes              ]
//    [static field size          ]
//    [nonstatic field size       ]
//    [static oop fields size     ]
//    [nonstatic oop maps size    ]
//    [has finalize method        ]
//    [deoptimization mark bit    ]
//    [initialization state       ]
//    [initializing thread        ]
//    [Java vtable length         ]
//    [oop map cache (stack maps) ]
//    [EMBEDDED Java vtable             ] size in words = vtable_len
//    [EMBEDDED nonstatic oop-map blocks] size in words = nonstatic_oop_map_size
//      The embedded nonstatic oop-map blocks are short pairs (offset, length)
//      indicating where oops are located in instances of this klass.
//    [EMBEDDED implementor of the interface] only exist for interface
//    [EMBEDDED host klass        ] only exist for an anonymous class (JSR 292 enabled)


// forward declaration for class -- see below for definition
class SuperTypeClosure;
class JNIid;
class jniIdMapBase;
class BreakpointInfo;
class fieldDescriptor;
class DepChange;
class nmethodBucket;
class PreviousVersionNode;
class JvmtiCachedClassFieldMap;
class MemberNameTable;

// This is used in iterators below.
class FieldClosure: public StackObj {
public:
  virtual void do_field(fieldDescriptor* fd) = 0;
};

#ifndef PRODUCT
// Print fields.
// If "obj" argument to constructor is NULL, prints static fields, otherwise prints non-static fields.
class FieldPrinter: public FieldClosure {
   oop _obj;
   outputStream* _st;
 public:
   FieldPrinter(outputStream* st, oop obj = NULL) : _obj(obj), _st(st) {}
   void do_field(fieldDescriptor* fd);
};
#endif  // !PRODUCT

// ValueObjs embedded in klass. Describes where oops are located in instances of
// this klass.
class OopMapBlock VALUE_OBJ_CLASS_SPEC {
 public:
  // Byte offset of the first oop mapped by this block.
  int offset() const          { return _offset; }
  void set_offset(int offset) { _offset = offset; }

  // Number of oops in this block.
  uint count() const         { return _count; }
  void set_count(uint count) { _count = count; }

  // sizeof(OopMapBlock) in HeapWords.
  static const int size_in_words() {
    return align_size_up(int(sizeof(OopMapBlock)), HeapWordSize) >>
      LogHeapWordSize;
  }

 private:
  int  _offset;
  uint _count;
};

class instanceKlass: public Klass {
  friend class VMStructs;
 public:
  // See "The Java Virtual Machine Specification" section 2.16.2-5 for a detailed description
  // of the class loading & initialization procedure, and the use of the states.
  enum ClassState {
    unparsable_by_gc = 0,               // object is not yet parsable by gc. Value of _init_state at object allocation.
    allocated,                          // allocated (but not yet linked)
    loaded,                             // loaded and inserted in class hierarchy (but not linked yet)
    linked,                             // successfully linked/verified (but not initialized yet)
    being_initialized,                  // currently running class initializer
    fully_initialized,                  // initialized (successfull final state)
    initialization_error                // error happened during initialization
  };

 public:
  oop* oop_block_beg() const { return adr_array_klasses(); }
  oop* oop_block_end() const { return adr_methods_default_annotations() + 1; }

  static int number_of_instance_classes() { return _total_instanceKlass_count; }

 private:
  static volatile int _total_instanceKlass_count;

 protected:
  //
  // The oop block.  See comment in klass.hpp before making changes.
  //

  // Array classes holding elements of this class.
  klassOop        _array_klasses;
  // Method array.
  objArrayOop     _methods;
  // Int array containing the original order of method in the class file (for
  // JVMTI).
  typeArrayOop    _method_ordering;
  // Interface (klassOops) this class declares locally to implement.
  objArrayOop     _local_interfaces;
  // Interface (klassOops) this class implements transitively.
  objArrayOop     _transitive_interfaces;
  // Instance and static variable information, starts with 6-tuples of shorts
  // [access, name index, sig index, initval index, low_offset, high_offset]
  // for all fields, followed by the generic signature data at the end of
  // the array. Only fields with generic signature attributes have the generic
  // signature data set in the array. The fields array looks like following:
  //
  // f1: [access, name index, sig index, initial value index, low_offset, high_offset]
  // f2: [access, name index, sig index, initial value index, low_offset, high_offset]
  //      ...
  // fn: [access, name index, sig index, initial value index, low_offset, high_offset]
  //     [generic signature index]
  //     [generic signature index]
  //     ...
  typeArrayOop    _fields;
  // Constant pool for this class.
  constantPoolOop _constants;
  // Class loader used to load this class, NULL if VM loader used.
  oop             _class_loader;
  // Protection domain.
  oop             _protection_domain;
  // Class signers.
  objArrayOop     _signers;
  // The InnerClasses attribute and EnclosingMethod attribute. The
  // _inner_classes is an array of shorts. If the class has InnerClasses
  // attribute, then the _inner_classes array begins with 4-tuples of shorts
  // [inner_class_info_index, outer_class_info_index,
  // inner_name_index, inner_class_access_flags] for the InnerClasses
  // attribute. If the EnclosingMethod attribute exists, it occupies the
  // last two shorts [class_index, method_index] of the array. If only
  // the InnerClasses attribute exists, the _inner_classes array length is
  // number_of_inner_classes * 4. If the class has both InnerClasses
  // and EnclosingMethod attributes the _inner_classes array length is
  // number_of_inner_classes * 4 + enclosing_method_attribute_size.
  typeArrayOop    _inner_classes;
  // Annotations for this class, or null if none.
  typeArrayOop    _class_annotations;
  // Annotation objects (byte arrays) for fields, or null if no annotations.
  // Indices correspond to entries (not indices) in fields array.
  objArrayOop     _fields_annotations;
  // Annotation objects (byte arrays) for methods, or null if no annotations.
  // Index is the idnum, which is initially the same as the methods array index.
  objArrayOop     _methods_annotations;
  // Annotation objects (byte arrays) for methods' parameters, or null if no
  // such annotations.
  // Index is the idnum, which is initially the same as the methods array index.
  objArrayOop     _methods_parameter_annotations;
  // Annotation objects (byte arrays) for methods' default values, or null if no
  // such annotations.
  // Index is the idnum, which is initially the same as the methods array index.
  objArrayOop     _methods_default_annotations;

  //
  // End of the oop block.
  //

  // Name of source file containing this klass, NULL if not specified.
  Symbol*         _source_file_name;
  // the source debug extension for this klass, NULL if not specified.
  // Specified as UTF-8 string without terminating zero byte in the classfile,
  // it is stored in the instanceklass as a NULL-terminated UTF-8 string
  char*           _source_debug_extension;
  // Generic signature, or null if none.
  Symbol*         _generic_signature;
  // Array name derived from this class which needs unreferencing
  // if this class is unloaded.
  Symbol*         _array_name;

  // Number of heapOopSize words used by non-static fields in this klass
  // (including inherited fields but after header_size()).
  int             _nonstatic_field_size;
  int             _static_field_size;    // number words used by static fields (oop and non-oop) in this klass
  u2              _static_oop_field_count;// number of static oop fields in this klass
  u2              _java_fields_count;    // The number of declared Java fields
  int             _nonstatic_oop_map_size;// size in words of nonstatic oop map blocks

  bool            _is_marked_dependent;  // used for marking during flushing and deoptimization
  enum {
    _misc_rewritten            = 1 << 0, // methods rewritten.
    _misc_has_nonstatic_fields = 1 << 1, // for sizing with UseCompressedOops
    _misc_should_verify_class  = 1 << 2, // allow caching of preverification
    _misc_is_anonymous         = 1 << 3  // has embedded _inner_classes field
  };
  u2              _misc_flags;
  u2              _minor_version;        // minor version number of class file
  u2              _major_version;        // major version number of class file
  Thread*         _init_thread;          // Pointer to current thread doing initialization (to handle recusive initialization)
  int             _vtable_len;           // length of Java vtable (in words)
  int             _itable_len;           // length of Java itable (in words)
  OopMapCache*    volatile _oop_map_cache;   // OopMapCache for all methods in the klass (allocated lazily)
  MemberNameTable* _member_names;        // Member names
  JNIid*          _jni_ids;              // First JNI identifier for static fields in this class
  jmethodID*      _methods_jmethod_ids;  // jmethodIDs corresponding to method_idnum, or NULL if none
  int*            _methods_cached_itable_indices;  // itable_index cache for JNI invoke corresponding to methods idnum, or NULL
  nmethodBucket*  _dependencies;         // list of dependent nmethods
  nmethod*        _osr_nmethods_head;    // Head of list of on-stack replacement nmethods for this class
  BreakpointInfo* _breakpoints;          // bpt lists, managed by methodOop
  // Array of interesting part(s) of the previous version(s) of this
  // instanceKlass. See PreviousVersionWalker below.
  GrowableArray<PreviousVersionNode *>* _previous_versions;
  // JVMTI fields can be moved to their own structure - see 6315920
  unsigned char * _cached_class_file_bytes;       // JVMTI: cached class file, before retransformable agent modified it in CFLH
  jint            _cached_class_file_len;         // JVMTI: length of above
  JvmtiCachedClassFieldMap* _jvmti_cached_class_field_map;  // JVMTI: used during heap iteration
  volatile u2     _idnum_allocated_count;         // JNI/JVMTI: increments with the addition of methods, old ids don't change

  // Class states are defined as ClassState (see above).
  // Place the _init_state here to utilize the unused 2-byte after
  // _idnum_allocated_count.
  u1              _init_state;                    // state of class

  u1              _reference_type;                // reference type

  // embedded Java vtable follows here
  // embedded Java itables follows here
  // embedded static fields follows here
  // embedded nonstatic oop-map blocks follows here
  // embedded implementor of this interface follows here
  //   The embedded implementor only exists if the current klass is an
  //   iterface. The possible values of the implementor fall into following
  //   three cases:
  //     NULL: no implementor.
  //     A klassOop that's not itself: one implementor.
  //     Itsef: more than one implementors.
  // embedded host klass follows here
  //   The embedded host klass only exists in an anonymous class for
  //   dynamic language support (JSR 292 enabled). The host class grants
  //   its access privileges to this class also. The host class is either
  //   named, or a previously loaded anonymous class. A non-anonymous class
  //   or an anonymous class loaded through normal classloading does not
  //   have this embedded field.
  //

  friend class instanceKlassKlass;
  friend class SystemDictionary;

 public:
  bool has_nonstatic_fields() const        {
    return (_misc_flags & _misc_has_nonstatic_fields) != 0;
  }
  void set_has_nonstatic_fields(bool b)    {
    if (b) {
      _misc_flags |= _misc_has_nonstatic_fields;
    } else {
      _misc_flags &= ~_misc_has_nonstatic_fields;
    }
  }

  // field sizes
  int nonstatic_field_size() const         { return _nonstatic_field_size; }
  void set_nonstatic_field_size(int size)  { _nonstatic_field_size = size; }

  int static_field_size() const            { return _static_field_size; }
  void set_static_field_size(int size)     { _static_field_size = size; }

  int static_oop_field_count() const       { return (int)_static_oop_field_count; }
  void set_static_oop_field_count(u2 size) { _static_oop_field_count = size; }

  // Java vtable
  int  vtable_length() const               { return _vtable_len; }
  void set_vtable_length(int len)          { _vtable_len = len; }

  // Java itable
  int  itable_length() const               { return _itable_len; }
  void set_itable_length(int len)          { _itable_len = len; }

  // array klasses
  klassOop array_klasses() const           { return _array_klasses; }
  void set_array_klasses(klassOop k)       { oop_store_without_check((oop*) &_array_klasses, (oop) k); }

  // methods
  objArrayOop methods() const              { return _methods; }
  void set_methods(objArrayOop a)          { oop_store_without_check((oop*) &_methods, (oop) a); }
  methodOop method_with_idnum(int idnum);

  // method ordering
  typeArrayOop method_ordering() const     { return _method_ordering; }
  void set_method_ordering(typeArrayOop m) { oop_store_without_check((oop*) &_method_ordering, (oop) m); }

  // interfaces
  objArrayOop local_interfaces() const          { return _local_interfaces; }
  void set_local_interfaces(objArrayOop a)      { oop_store_without_check((oop*) &_local_interfaces, (oop) a); }
  objArrayOop transitive_interfaces() const     { return _transitive_interfaces; }
  void set_transitive_interfaces(objArrayOop a) { oop_store_without_check((oop*) &_transitive_interfaces, (oop) a); }

 private:
  friend class fieldDescriptor;
  FieldInfo* field(int index) const { return FieldInfo::from_field_array(_fields, index); }

 public:
  int     field_offset      (int index) const { return field(index)->offset(); }
  int     field_access_flags(int index) const { return field(index)->access_flags(); }
  Symbol* field_name        (int index) const { return field(index)->name(constants()); }
  Symbol* field_signature   (int index) const { return field(index)->signature(constants()); }

  // Number of Java declared fields
  int java_fields_count() const           { return (int)_java_fields_count; }

  typeArrayOop fields() const              { return _fields; }

  void set_fields(typeArrayOop f, u2 java_fields_count) {
    oop_store_without_check((oop*) &_fields, (oop) f);
    _java_fields_count = java_fields_count;
  }

  // inner classes
  typeArrayOop inner_classes() const       { return _inner_classes; }
  void set_inner_classes(typeArrayOop f)   { oop_store_without_check((oop*) &_inner_classes, (oop) f); }

  enum InnerClassAttributeOffset {
    // From http://mirror.eng/products/jdk/1.1/docs/guide/innerclasses/spec/innerclasses.doc10.html#18814
    inner_class_inner_class_info_offset = 0,
    inner_class_outer_class_info_offset = 1,
    inner_class_inner_name_offset = 2,
    inner_class_access_flags_offset = 3,
    inner_class_next_offset = 4
  };

  enum EnclosingMethodAttributeOffset {
    enclosing_method_class_index_offset = 0,
    enclosing_method_method_index_offset = 1,
    enclosing_method_attribute_size = 2
  };

  // method override check
  bool is_override(methodHandle super_method, Handle targetclassloader, Symbol* targetclassname, TRAPS);

  // package
  bool is_same_class_package(klassOop class2);
  bool is_same_class_package(oop classloader2, Symbol* classname2);
  static bool is_same_class_package(oop class_loader1, Symbol* class_name1, oop class_loader2, Symbol* class_name2);

  // find an enclosing class (defined where original code was, in jvm.cpp!)
  klassOop compute_enclosing_class(bool* inner_is_member, TRAPS) {
    instanceKlassHandle self(THREAD, this->as_klassOop());
    return compute_enclosing_class_impl(self, inner_is_member, THREAD);
  }
  static klassOop compute_enclosing_class_impl(instanceKlassHandle self,
                                               bool* inner_is_member, TRAPS);

  // tell if two classes have the same enclosing class (at package level)
  bool is_same_package_member(klassOop class2, TRAPS) {
    instanceKlassHandle self(THREAD, this->as_klassOop());
    return is_same_package_member_impl(self, class2, THREAD);
  }
  static bool is_same_package_member_impl(instanceKlassHandle self,
                                          klassOop class2, TRAPS);

  // initialization state
  bool is_loaded() const                   { return _init_state >= loaded; }
  bool is_linked() const                   { return _init_state >= linked; }
  bool is_initialized() const              { return _init_state == fully_initialized; }
  bool is_not_initialized() const          { return _init_state <  being_initialized; }
  bool is_being_initialized() const        { return _init_state == being_initialized; }
  bool is_in_error_state() const           { return _init_state == initialization_error; }
  bool is_reentrant_initialization(Thread *thread)  { return thread == _init_thread; }
  ClassState  init_state()                 { return (ClassState)_init_state; }
  bool is_rewritten() const                { return (_misc_flags & _misc_rewritten) != 0; }

  // defineClass specified verification
  bool should_verify_class() const         {
    return (_misc_flags & _misc_should_verify_class) != 0;
  }
  void set_should_verify_class(bool value) {
    if (value) {
      _misc_flags |= _misc_should_verify_class;
    } else {
      _misc_flags &= ~_misc_should_verify_class;
    }
  }

  // marking
  bool is_marked_dependent() const         { return _is_marked_dependent; }
  void set_is_marked_dependent(bool value) { _is_marked_dependent = value; }

  // initialization (virtuals from Klass)
  bool should_be_initialized() const;  // means that initialize should be called
  void initialize(TRAPS);
  void link_class(TRAPS);
  bool link_class_or_fail(TRAPS); // returns false on failure
  void unlink_class();
  void rewrite_class(TRAPS);
  void relocate_and_link_methods(TRAPS);
  methodOop class_initializer();

  // set the class to initialized if no static initializer is present
  void eager_initialize(Thread *thread);

  // reference type
  ReferenceType reference_type() const     { return (ReferenceType)_reference_type; }
  void set_reference_type(ReferenceType t) {
    assert(t == (u1)t, "overflow");
    _reference_type = (u1)t;
  }

  static ByteSize reference_type_offset() { return in_ByteSize(sizeof(klassOopDesc) + offset_of(instanceKlass, _reference_type)); }

  // find local field, returns true if found
  bool find_local_field(Symbol* name, Symbol* sig, fieldDescriptor* fd) const;
  // find field in direct superinterfaces, returns the interface in which the field is defined
  klassOop find_interface_field(Symbol* name, Symbol* sig, fieldDescriptor* fd) const;
  // find field according to JVM spec 5.4.3.2, returns the klass in which the field is defined
  klassOop find_field(Symbol* name, Symbol* sig, fieldDescriptor* fd) const;
  // find instance or static fields according to JVM spec 5.4.3.2, returns the klass in which the field is defined
  klassOop find_field(Symbol* name, Symbol* sig, bool is_static, fieldDescriptor* fd) const;

  // find a non-static or static field given its offset within the class.
  bool contains_field_offset(int offset) {
    return instanceOopDesc::contains_field_offset(offset, nonstatic_field_size());
  }

  bool find_local_field_from_offset(int offset, bool is_static, fieldDescriptor* fd) const;
  bool find_field_from_offset(int offset, bool is_static, fieldDescriptor* fd) const;

  // find a local method (returns NULL if not found)
  methodOop find_method(Symbol* name, Symbol* signature) const;
  static methodOop find_method(objArrayOop methods, Symbol* name, Symbol* signature);

  // find a local method, but skip static methods
  methodOop find_instance_method(Symbol* name, Symbol* signature,
                                 PrivateLookupMode private_mode = Klass::find_private);
  static methodOop find_instance_method(objArrayOop methods, Symbol* name, Symbol* signature,
                                        PrivateLookupMode private_mode = Klass::find_private);

  // true if method matches signature and conforms to skipping_X conditions.
  static bool method_matches(methodOop m, Symbol* signature, bool skipping_static, bool skipping_private);

  // find a local method index in default_methods (returns -1 if not found)

  static int find_method_index(objArrayOop methods, Symbol* name, Symbol* signature, bool skipping_static, bool skipping_private);

  // lookup operation (returns NULL if not found)
  methodOop uncached_lookup_method(Symbol* name, Symbol* signature) const;

  // lookup a method in all the interfaces that this class implements
  // (returns NULL if not found)
  methodOop lookup_method_in_all_interfaces(Symbol* name, Symbol* signature) const;

  // Find method indices by name.  If a method with the specified name is
  // found the index to the first method is returned, and 'end' is filled in
  // with the index of first non-name-matching method.  If no method is found
  // -1 is returned.
  int find_method_by_name(Symbol* name, int* end);
  static int find_method_by_name(objArrayOop methods, Symbol* name, int* end);

  // constant pool
  constantPoolOop constants() const        { return _constants; }
  void set_constants(constantPoolOop c)    { oop_store_without_check((oop*) &_constants, (oop) c); }

  // class loader
  oop class_loader() const                 { return _class_loader; }
  void set_class_loader(oop l)             { oop_store((oop*) &_class_loader, l); }

  // protection domain
  oop protection_domain()                  { return _protection_domain; }
  void set_protection_domain(oop pd)       { oop_store((oop*) &_protection_domain, pd); }

  // host class
  oop host_klass() const                   {
    oop* hk = adr_host_klass();
    if (hk == NULL) {
      assert(!is_anonymous(), "Anonymous classes have host klasses");
      return NULL;
    } else {
      assert(is_anonymous(), "Only anonymous classes have host klasses");
      return *hk;
    }
  }
  void set_host_klass(oop host)            {
    assert(is_anonymous(), "not anonymous");
    oop* addr = adr_host_klass();
    assert(addr != NULL, "no reversed space");
    oop_store(addr, host);
  }
  bool is_anonymous() const                {
    return (_misc_flags & _misc_is_anonymous) != 0;
  }
  void set_is_anonymous(bool value)        {
    if (value) {
      _misc_flags |= _misc_is_anonymous;
    } else {
      _misc_flags &= ~_misc_is_anonymous;
    }
  }

  // signers
  objArrayOop signers() const              { return _signers; }
  void set_signers(objArrayOop s)          { oop_store((oop*) &_signers, oop(s)); }

  // source file name
  Symbol* source_file_name() const         { return _source_file_name; }
  void set_source_file_name(Symbol* n);

  // minor and major version numbers of class file
  u2 minor_version() const                 { return _minor_version; }
  void set_minor_version(u2 minor_version) { _minor_version = minor_version; }
  u2 major_version() const                 { return _major_version; }
  void set_major_version(u2 major_version) { _major_version = major_version; }

  // source debug extension
  char* source_debug_extension() const     { return _source_debug_extension; }
  void set_source_debug_extension(char* array, int length);

  // symbol unloading support (refcount already added)
  Symbol* array_name()                     { return _array_name; }
  void set_array_name(Symbol* name)        { assert(_array_name == NULL, "name already created"); _array_name = name; }

  // nonstatic oop-map blocks
  static int nonstatic_oop_map_size(unsigned int oop_map_count) {
    return oop_map_count * OopMapBlock::size_in_words();
  }
  unsigned int nonstatic_oop_map_count() const {
    return _nonstatic_oop_map_size / OopMapBlock::size_in_words();
  }
  int nonstatic_oop_map_size() const { return _nonstatic_oop_map_size; }
  void set_nonstatic_oop_map_size(int words) {
    _nonstatic_oop_map_size = words;
  }

  // RedefineClasses() support for previous versions:
  void add_previous_version(instanceKlassHandle ikh, BitMap *emcp_methods,
         int emcp_method_count);
  // If the _previous_versions array is non-NULL, then this klass
  // has been redefined at least once even if we aren't currently
  // tracking a previous version.
  bool has_been_redefined() const { return _previous_versions != NULL; }
  bool has_previous_version() const;
  void init_previous_versions() {
    _previous_versions = NULL;
  }
  GrowableArray<PreviousVersionNode *>* previous_versions() const {
    return _previous_versions;
  }

  // JVMTI: Support for caching a class file before it is modified by an agent that can do retransformation
  void set_cached_class_file(unsigned char *class_file_bytes,
                             jint class_file_len)     { _cached_class_file_len = class_file_len;
                                                        _cached_class_file_bytes = class_file_bytes; }
  jint get_cached_class_file_len()                    { return _cached_class_file_len; }
  unsigned char * get_cached_class_file_bytes()       { return _cached_class_file_bytes; }

  // JVMTI: Support for caching of field indices, types, and offsets
  void set_jvmti_cached_class_field_map(JvmtiCachedClassFieldMap* descriptor) {
    _jvmti_cached_class_field_map = descriptor;
  }
  JvmtiCachedClassFieldMap* jvmti_cached_class_field_map() const {
    return _jvmti_cached_class_field_map;
  }

  // for adding methods, constMethodOopDesc::UNSET_IDNUM means no more ids available
  inline u2 next_method_idnum();
  void set_initial_method_idnum(u2 value)             { _idnum_allocated_count = value; }

  // generics support
  Symbol* generic_signature() const                   { return _generic_signature; }
  void set_generic_signature(Symbol* sig)             { _generic_signature = sig; }

  u2 enclosing_method_data(int offset);
  u2 enclosing_method_class_index() {
    return enclosing_method_data(enclosing_method_class_index_offset);
  }
  u2 enclosing_method_method_index() {
    return enclosing_method_data(enclosing_method_method_index_offset);
  }
  void set_enclosing_method_indices(u2 class_index,
                                    u2 method_index);

  // jmethodID support
  static jmethodID get_jmethod_id(instanceKlassHandle ik_h,
                     methodHandle method_h);
  static jmethodID get_jmethod_id_fetch_or_update(instanceKlassHandle ik_h,
                     size_t idnum, jmethodID new_id, jmethodID* new_jmeths,
                     jmethodID* to_dealloc_id_p,
                     jmethodID** to_dealloc_jmeths_p);
  static void get_jmethod_id_length_value(jmethodID* cache, size_t idnum,
                size_t *length_p, jmethodID* id_p);
  jmethodID jmethod_id_or_null(methodOop method);

  // cached itable index support
  void set_cached_itable_index(size_t idnum, int index);
  int cached_itable_index(size_t idnum);

  // annotations support
  typeArrayOop class_annotations() const              { return _class_annotations; }
  objArrayOop fields_annotations() const              { return _fields_annotations; }
  objArrayOop methods_annotations() const             { return _methods_annotations; }
  objArrayOop methods_parameter_annotations() const   { return _methods_parameter_annotations; }
  objArrayOop methods_default_annotations() const     { return _methods_default_annotations; }
  void set_class_annotations(typeArrayOop md)            { oop_store_without_check((oop*)&_class_annotations, (oop)md); }
  void set_fields_annotations(objArrayOop md)            { set_annotations(md, &_fields_annotations); }
  void set_methods_annotations(objArrayOop md)           { set_annotations(md, &_methods_annotations); }
  void set_methods_parameter_annotations(objArrayOop md) { set_annotations(md, &_methods_parameter_annotations); }
  void set_methods_default_annotations(objArrayOop md)   { set_annotations(md, &_methods_default_annotations); }
  typeArrayOop get_method_annotations_of(int idnum)
                                                { return get_method_annotations_from(idnum, _methods_annotations); }
  typeArrayOop get_method_parameter_annotations_of(int idnum)
                                                { return get_method_annotations_from(idnum, _methods_parameter_annotations); }
  typeArrayOop get_method_default_annotations_of(int idnum)
                                                { return get_method_annotations_from(idnum, _methods_default_annotations); }
  void set_method_annotations_of(int idnum, typeArrayOop anno)
                                                { set_methods_annotations_of(idnum, anno, &_methods_annotations); }
  void set_method_parameter_annotations_of(int idnum, typeArrayOop anno)
                                                { set_methods_annotations_of(idnum, anno, &_methods_parameter_annotations); }
  void set_method_default_annotations_of(int idnum, typeArrayOop anno)
                                                { set_methods_annotations_of(idnum, anno, &_methods_default_annotations); }

  // allocation
  DEFINE_ALLOCATE_PERMANENT(instanceKlass);
  instanceOop allocate_instance(TRAPS);
  instanceOop allocate_permanent_instance(TRAPS);

  // additional member function to return a handle
  instanceHandle allocate_instance_handle(TRAPS)      { return instanceHandle(THREAD, allocate_instance(THREAD)); }

  objArrayOop allocate_objArray(int n, int length, TRAPS);
  // Helper function
  static instanceOop register_finalizer(instanceOop i, TRAPS);

  // Check whether reflection/jni/jvm code is allowed to instantiate this class;
  // if not, throw either an Error or an Exception.
  virtual void check_valid_for_instantiation(bool throwError, TRAPS);

  // initialization
  void call_class_initializer(TRAPS);
  void set_initialization_state_and_notify(ClassState state, TRAPS);

  // OopMapCache support
  OopMapCache* oop_map_cache()               { return _oop_map_cache; }
  void set_oop_map_cache(OopMapCache *cache) { _oop_map_cache = cache; }
  void mask_for(methodHandle method, int bci, InterpreterOopMap* entry);

  // JNI identifier support (for static fields - for jni performance)
  JNIid* jni_ids()                               { return _jni_ids; }
  void set_jni_ids(JNIid* ids)                   { _jni_ids = ids; }
  JNIid* jni_id_for(int offset);

  // maintenance of deoptimization dependencies
  int mark_dependent_nmethods(DepChange& changes);
  void add_dependent_nmethod(nmethod* nm);
  void remove_dependent_nmethod(nmethod* nm);

  // On-stack replacement support
  nmethod* osr_nmethods_head() const         { return _osr_nmethods_head; };
  void set_osr_nmethods_head(nmethod* h)     { _osr_nmethods_head = h; };
  void add_osr_nmethod(nmethod* n);
  void remove_osr_nmethod(nmethod* n);
  nmethod* lookup_osr_nmethod(const methodOop m, int bci, int level, bool match_level) const;

  // Breakpoint support (see methods on methodOop for details)
  BreakpointInfo* breakpoints() const       { return _breakpoints; };
  void set_breakpoints(BreakpointInfo* bps) { _breakpoints = bps; };

  // support for stub routines
  static ByteSize init_state_offset()  { return in_ByteSize(sizeof(klassOopDesc) + offset_of(instanceKlass, _init_state)); }
  TRACE_DEFINE_OFFSET;
  static ByteSize init_thread_offset() { return in_ByteSize(sizeof(klassOopDesc) + offset_of(instanceKlass, _init_thread)); }

  // subclass/subinterface checks
  bool implements_interface(klassOop k) const;

  // Access to the implementor of an interface.
  klassOop implementor() const
  {
    klassOop* k = (klassOop*)adr_implementor();
    if (k == NULL) {
      return NULL;
    } else {
      return *k;
    }
  }

  void set_implementor(klassOop k) {
    assert(is_interface(), "not interface");
    oop* addr = adr_implementor();
    oop_store_without_check(addr, k);
  }

  int  nof_implementors() const       {
    klassOop k = implementor();
    if (k == NULL) {
      return 0;
    } else if (k != this->as_klassOop()) {
      return 1;
    } else {
      return 2;
    }
  }

  void add_implementor(klassOop k);  // k is a new class that implements this interface
  void init_implementor();           // initialize

  // link this class into the implementors list of every interface it implements
  void process_interfaces(Thread *thread);

  // virtual operations from Klass
  bool is_leaf_class() const               { return _subklass == NULL; }
  objArrayOop compute_secondary_supers(int num_extra_slots, TRAPS);
  bool compute_is_subtype_of(klassOop k);
  bool can_be_primary_super_slow() const;
  klassOop java_super() const              { return super(); }
  int oop_size(oop obj)  const             { return size_helper(); }
  int klass_oop_size() const               { return object_size(); }
  bool oop_is_instance_slow() const        { return true; }

  // Iterators
  void do_local_static_fields(FieldClosure* cl);
  void do_nonstatic_fields(FieldClosure* cl); // including inherited fields
  void do_local_static_fields(void f(fieldDescriptor*, TRAPS), TRAPS);

  void methods_do(void f(methodOop method));
  void array_klasses_do(void f(klassOop k));
  void with_array_klasses_do(void f(klassOop k));
  bool super_types_do(SuperTypeClosure* blk);

  // Casting from klassOop
  static instanceKlass* cast(klassOop k) {
    assert(k->is_klass(), "must be");
    Klass* kp = k->klass_part();
    assert(kp->null_vtbl() || kp->oop_is_instance_slow(), "cast to instanceKlass");
    return (instanceKlass*) kp;
  }

  // Sizing (in words)
  static int header_size()            { return align_object_offset(oopDesc::header_size() + sizeof(instanceKlass)/HeapWordSize); }

  int object_size() const
  {
    int vtable_size = align_object_offset(vtable_length());
    int itable_size = align_object_offset(itable_length());
    int aligned_nonstatic_oop_map_size = is_interface() || is_anonymous() ?
                                        align_object_offset(nonstatic_oop_map_size()) :
                                        nonstatic_oop_map_size();
    int interface_implementor_size = is_interface() ? (int) sizeof(klassOop) / HeapWordSize : 0;
    int host_klass_size = is_anonymous() ? (int) sizeof(klassOop) / HeapWordSize : 0;

    return object_size(vtable_size + itable_size + aligned_nonstatic_oop_map_size +
                       interface_implementor_size + host_klass_size);
  }
  static int vtable_start_offset()    { return header_size(); }
  static int vtable_length_offset()   { return oopDesc::header_size() + offset_of(instanceKlass, _vtable_len) / HeapWordSize; }
  static int object_size(int extra)   { return align_object_size(header_size() + extra); }

  intptr_t* start_of_vtable() const        { return ((intptr_t*)as_klassOop()) + vtable_start_offset(); }
  intptr_t* start_of_itable() const        { return start_of_vtable() + align_object_offset(vtable_length()); }
  int  itable_offset_in_words() const { return start_of_itable() - (intptr_t*)as_klassOop(); }

  intptr_t* end_of_itable() const          { return start_of_itable() + itable_length(); }

  address static_field_addr(int offset);

  OopMapBlock* start_of_nonstatic_oop_maps() const {
    return (OopMapBlock*)(start_of_itable() + align_object_offset(itable_length()));
  }

  oop* adr_implementor() const {
    if (is_interface()) {
      return (oop*)(start_of_nonstatic_oop_maps() +
                    nonstatic_oop_map_count());
    } else {
      return NULL;
    }
  };

  oop* adr_host_klass() const {
    if (is_anonymous()) {
      oop* adr_impl = adr_implementor();
      if (adr_impl != NULL) {
        return adr_impl + 1;
      } else {
        return (oop*)(start_of_nonstatic_oop_maps() +
                      nonstatic_oop_map_count());
      }
    } else {
      return NULL;
    }
  }

  // Allocation profiling support
  juint alloc_size() const            { return _alloc_count * size_helper(); }
  void set_alloc_size(juint n)        {}

  // Use this to return the size of an instance in heap words:
  int size_helper() const {
    return layout_helper_to_size_helper(layout_helper());
  }

  // This bit is initialized in classFileParser.cpp.
  // It is false under any of the following conditions:
  //  - the class is abstract (including any interface)
  //  - the class has a finalizer (if !RegisterFinalizersAtInit)
  //  - the class size is larger than FastAllocateSizeLimit
  //  - the class is java/lang/Class, which cannot be allocated directly
  bool can_be_fastpath_allocated() const {
    return !layout_helper_needs_slow_path(layout_helper());
  }

  // Java vtable/itable
  klassVtable* vtable() const;        // return new klassVtable wrapper
  inline methodOop method_at_vtable(int index);
  klassItable* itable() const;        // return new klassItable wrapper
  methodOop method_at_itable(klassOop holder, int index, TRAPS);

  // Garbage collection
  void oop_follow_contents(oop obj);
  int  oop_adjust_pointers(oop obj);
  bool object_is_parsable() const { return _init_state != unparsable_by_gc; }
       // Value of _init_state must be zero (unparsable_by_gc) when klass field is set.

  void follow_weak_klass_links(
    BoolObjectClosure* is_alive, OopClosure* keep_alive);
  void release_C_heap_structures();

  // Parallel Scavenge and Parallel Old
  PARALLEL_GC_DECLS

  // Naming
  const char* signature_name() const;

  // Iterators
  int oop_oop_iterate(oop obj, OopClosure* blk) {
    return oop_oop_iterate_v(obj, blk);
  }

  int oop_oop_iterate_m(oop obj, OopClosure* blk, MemRegion mr) {
    return oop_oop_iterate_v_m(obj, blk, mr);
  }

#define InstanceKlass_OOP_OOP_ITERATE_DECL(OopClosureType, nv_suffix)      \
  int  oop_oop_iterate##nv_suffix(oop obj, OopClosureType* blk);           \
  int  oop_oop_iterate##nv_suffix##_m(oop obj, OopClosureType* blk,        \
                                      MemRegion mr);

  ALL_OOP_OOP_ITERATE_CLOSURES_1(InstanceKlass_OOP_OOP_ITERATE_DECL)
  ALL_OOP_OOP_ITERATE_CLOSURES_2(InstanceKlass_OOP_OOP_ITERATE_DECL)

#ifndef SERIALGC
#define InstanceKlass_OOP_OOP_ITERATE_BACKWARDS_DECL(OopClosureType, nv_suffix) \
  int  oop_oop_iterate_backwards##nv_suffix(oop obj, OopClosureType* blk);

  ALL_OOP_OOP_ITERATE_CLOSURES_1(InstanceKlass_OOP_OOP_ITERATE_BACKWARDS_DECL)
  ALL_OOP_OOP_ITERATE_CLOSURES_2(InstanceKlass_OOP_OOP_ITERATE_BACKWARDS_DECL)
#endif // !SERIALGC

private:
  // initialization state
#ifdef ASSERT
  void set_init_state(ClassState state);
#else
  void set_init_state(ClassState state) { _init_state = (u1)state; }
#endif
  void set_rewritten()                  { _misc_flags |= _misc_rewritten; }
  void set_init_thread(Thread *thread)  { _init_thread = thread; }

  u2 idnum_allocated_count() const      { return _idnum_allocated_count; }
  // The RedefineClasses() API can cause new method idnums to be needed
  // which will cause the caches to grow. Safety requires different
  // cache management logic if the caches can grow instead of just
  // going from NULL to non-NULL.
  bool idnum_can_increment() const      { return has_been_redefined(); }
  jmethodID* methods_jmethod_ids_acquire() const
         { return (jmethodID*)OrderAccess::load_ptr_acquire(&_methods_jmethod_ids); }
  void release_set_methods_jmethod_ids(jmethodID* jmeths)
         { OrderAccess::release_store_ptr(&_methods_jmethod_ids, jmeths); }

  int* methods_cached_itable_indices_acquire() const
         { return (int*)OrderAccess::load_ptr_acquire(&_methods_cached_itable_indices); }
  void release_set_methods_cached_itable_indices(int* indices)
         { OrderAccess::release_store_ptr(&_methods_cached_itable_indices, indices); }

  inline typeArrayOop get_method_annotations_from(int idnum, objArrayOop annos);
  void set_annotations(objArrayOop md, objArrayOop* md_p)  { oop_store_without_check((oop*)md_p, (oop)md); }
  void set_methods_annotations_of(int idnum, typeArrayOop anno, objArrayOop* md_p);

  // Offsets for memory management
  oop* adr_array_klasses() const     { return (oop*)&this->_array_klasses;}
  oop* adr_methods() const           { return (oop*)&this->_methods;}
  oop* adr_method_ordering() const   { return (oop*)&this->_method_ordering;}
  oop* adr_local_interfaces() const  { return (oop*)&this->_local_interfaces;}
  oop* adr_transitive_interfaces() const  { return (oop*)&this->_transitive_interfaces;}
  oop* adr_fields() const            { return (oop*)&this->_fields;}
  oop* adr_constants() const         { return (oop*)&this->_constants;}
  oop* adr_class_loader() const      { return (oop*)&this->_class_loader;}
  oop* adr_protection_domain() const { return (oop*)&this->_protection_domain;}
  oop* adr_signers() const           { return (oop*)&this->_signers;}
  oop* adr_inner_classes() const     { return (oop*)&this->_inner_classes;}
  oop* adr_methods_jmethod_ids() const             { return (oop*)&this->_methods_jmethod_ids;}
  oop* adr_methods_cached_itable_indices() const   { return (oop*)&this->_methods_cached_itable_indices;}
  oop* adr_class_annotations() const   { return (oop*)&this->_class_annotations;}
  oop* adr_fields_annotations() const  { return (oop*)&this->_fields_annotations;}
  oop* adr_methods_annotations() const { return (oop*)&this->_methods_annotations;}
  oop* adr_methods_parameter_annotations() const { return (oop*)&this->_methods_parameter_annotations;}
  oop* adr_methods_default_annotations() const { return (oop*)&this->_methods_default_annotations;}

  // Static methods that are used to implement member methods where an exposed this pointer
  // is needed due to possible GCs
  static bool link_class_impl                           (instanceKlassHandle this_oop, bool throw_verifyerror, TRAPS);
  static bool verify_code                               (instanceKlassHandle this_oop, bool throw_verifyerror, TRAPS);
  static void initialize_impl                           (instanceKlassHandle this_oop, TRAPS);
  static void eager_initialize_impl                     (instanceKlassHandle this_oop);
  static void set_initialization_state_and_notify_impl  (instanceKlassHandle this_oop, ClassState state, TRAPS);
  static void call_class_initializer_impl               (instanceKlassHandle this_oop, TRAPS);
  static klassOop array_klass_impl                      (instanceKlassHandle this_oop, bool or_null, int n, TRAPS);
  static void do_local_static_fields_impl               (instanceKlassHandle this_oop, void f(fieldDescriptor* fd, TRAPS), TRAPS);
  /* jni_id_for_impl for jfieldID only */
  static JNIid* jni_id_for_impl                         (instanceKlassHandle this_oop, int offset);

  // Returns the array class for the n'th dimension
  klassOop array_klass_impl(bool or_null, int n, TRAPS);

  // Returns the array class with this class as element type
  klassOop array_klass_impl(bool or_null, TRAPS);

  // find a local method (returns NULL if not found)
  static methodOop find_method_impl(objArrayOop methods, Symbol* name, Symbol* signature, bool skipping_static, bool skipping_private);

public:
  // sharing support
  virtual void remove_unshareable_info();
  virtual void shared_symbols_iterate(SymbolClosure* closure);

  // jvm support
  jint compute_modifier_flags(TRAPS) const;

  // JSR-292 support
  MemberNameTable* member_names() { return _member_names; }
  void set_member_names(MemberNameTable* member_names) { _member_names = member_names; }
  bool add_member_name(Handle member_name);

public:
  // JVMTI support
  jint jvmti_class_status() const;

 public:
  // Printing
  void oop_print_value_on(oop obj, outputStream* st);
#ifndef PRODUCT
  void oop_print_on      (oop obj, outputStream* st);

  void print_dependent_nmethods(bool verbose = false);
  bool is_dependent_nmethod(nmethod* nm);
#endif

  // Verification
  const char* internal_name() const;
  void oop_verify_on(oop obj, outputStream* st);
};

inline methodOop instanceKlass::method_at_vtable(int index)  {
#ifndef PRODUCT
  assert(index >= 0, "valid vtable index");
  if (DebugVtables) {
    verify_vtable_index(index);
  }
#endif
  vtableEntry* ve = (vtableEntry*)start_of_vtable();
  return ve[index].method();
}

inline typeArrayOop instanceKlass::get_method_annotations_from(int idnum, objArrayOop annos) {
  if (annos == NULL || annos->length() <= idnum) {
    return NULL;
  }
  return typeArrayOop(annos->obj_at(idnum));
}

// for adding methods
// UNSET_IDNUM return means no more ids available
inline u2 instanceKlass::next_method_idnum() {
  if (_idnum_allocated_count == constMethodOopDesc::MAX_IDNUM) {
    return constMethodOopDesc::UNSET_IDNUM; // no more ids available
  } else {
    return _idnum_allocated_count++;
  }
}


/* JNIid class for jfieldIDs only */
class JNIid: public CHeapObj<mtClass> {
  friend class VMStructs;
 private:
  klassOop           _holder;
  JNIid*             _next;
  int                _offset;
#ifdef ASSERT
  bool               _is_static_field_id;
#endif

 public:
  // Accessors
  klassOop holder() const         { return _holder; }
  int offset() const              { return _offset; }
  JNIid* next()                   { return _next; }
  // Constructor
  JNIid(klassOop holder, int offset, JNIid* next);
  // Identifier lookup
  JNIid* find(int offset);

  bool find_local_field(fieldDescriptor* fd) {
    return instanceKlass::cast(holder())->find_local_field_from_offset(offset(), true, fd);
  }

  // Garbage collection support
  oop* holder_addr() { return (oop*)&_holder; }
  void oops_do(OopClosure* f);
  static void deallocate(JNIid* id);
  // Debugging
#ifdef ASSERT
  bool is_static_field_id() const { return _is_static_field_id; }
  void set_is_static_field_id()   { _is_static_field_id = true; }
#endif
  void verify(klassOop holder);
};


// If breakpoints are more numerous than just JVMTI breakpoints,
// consider compressing this data structure.
// It is currently a simple linked list defined in methodOop.hpp.

class BreakpointInfo;


// A collection point for interesting information about the previous
// version(s) of an instanceKlass. This class uses weak references to
// the information so that the information may be collected as needed
// by the system. If the information is shared, then a regular
// reference must be used because a weak reference would be seen as
// collectible. A GrowableArray of PreviousVersionNodes is attached
// to the instanceKlass as needed. See PreviousVersionWalker below.
class PreviousVersionNode : public CHeapObj<mtClass> {
 private:
  // A shared ConstantPool is never collected so we'll always have
  // a reference to it so we can update items in the cache. We'll
  // have a weak reference to a non-shared ConstantPool until all
  // of the methods (EMCP or obsolete) have been collected; the
  // non-shared ConstantPool becomes collectible at that point.
  jobject _prev_constant_pool;  // regular or weak reference
  bool    _prev_cp_is_weak;     // true if not a shared ConstantPool

  // If the previous version of the instanceKlass doesn't have any
  // EMCP methods, then _prev_EMCP_methods will be NULL. If all the
  // EMCP methods have been collected, then _prev_EMCP_methods can
  // have a length of zero.
  GrowableArray<jweak>* _prev_EMCP_methods;

public:
  PreviousVersionNode(jobject prev_constant_pool, bool prev_cp_is_weak,
    GrowableArray<jweak>* prev_EMCP_methods);
  ~PreviousVersionNode();
  jobject prev_constant_pool() const {
    return _prev_constant_pool;
  }
  GrowableArray<jweak>* prev_EMCP_methods() const {
    return _prev_EMCP_methods;
  }
};


// A Handle-ized version of PreviousVersionNode.
class PreviousVersionInfo : public ResourceObj {
 private:
  constantPoolHandle   _prev_constant_pool_handle;
  // If the previous version of the instanceKlass doesn't have any
  // EMCP methods, then _prev_EMCP_methods will be NULL. Since the
  // methods cannot be collected while we hold a handle,
  // _prev_EMCP_methods should never have a length of zero.
  GrowableArray<methodHandle>* _prev_EMCP_method_handles;

public:
  PreviousVersionInfo(PreviousVersionNode *pv_node);
  ~PreviousVersionInfo();
  constantPoolHandle prev_constant_pool_handle() const {
    return _prev_constant_pool_handle;
  }
  GrowableArray<methodHandle>* prev_EMCP_method_handles() const {
    return _prev_EMCP_method_handles;
  }
};


// Helper object for walking previous versions. This helper cleans up
// the Handles that it allocates when the helper object is destroyed.
// The PreviousVersionInfo object returned by next_previous_version()
// is only valid until a subsequent call to next_previous_version() or
// the helper object is destroyed.
class PreviousVersionWalker : public StackObj {
 private:
  GrowableArray<PreviousVersionNode *>* _previous_versions;
  int                                   _current_index;
  // Fields for cleaning up when we are done walking the previous versions:
  // A HandleMark for the PreviousVersionInfo handles:
  HandleMark                            _hm;

  // It would be nice to have a ResourceMark field in this helper also,
  // but the ResourceMark code says to be careful to delete handles held
  // in GrowableArrays _before_ deleting the GrowableArray. Since we
  // can't guarantee the order in which the fields are destroyed, we
  // have to let the creator of the PreviousVersionWalker object do
  // the right thing. Also, adding a ResourceMark here causes an
  // include loop.

  // A pointer to the current info object so we can handle the deletes.
  PreviousVersionInfo *                 _current_p;

 public:
  PreviousVersionWalker(instanceKlass *ik);
  ~PreviousVersionWalker();

  // Return the interesting information for the next previous version
  // of the klass. Returns NULL if there are no more previous versions.
  PreviousVersionInfo* next_previous_version();
};


//
// nmethodBucket is used to record dependent nmethods for
// deoptimization.  nmethod dependencies are actually <klass, method>
// pairs but we really only care about the klass part for purposes of
// finding nmethods which might need to be deoptimized.  Instead of
// recording the method, a count of how many times a particular nmethod
// was recorded is kept.  This ensures that any recording errors are
// noticed since an nmethod should be removed as many times are it's
// added.
//
class nmethodBucket: public CHeapObj<mtClass> {
  friend class VMStructs;
 private:
  nmethod*       _nmethod;
  int            _count;
  nmethodBucket* _next;

 public:
  nmethodBucket(nmethod* nmethod, nmethodBucket* next) {
    _nmethod = nmethod;
    _next = next;
    _count = 1;
  }
  int count()                             { return _count; }
  int increment()                         { _count += 1; return _count; }
  int decrement()                         { _count -= 1; assert(_count >= 0, "don't underflow"); return _count; }
  nmethodBucket* next()                   { return _next; }
  void set_next(nmethodBucket* b)         { _next = b; }
  nmethod* get_nmethod()                  { return _nmethod; }
};

// An iterator that's used to access the inner classes indices in the
// instanceKlass::_inner_classes array.
class InnerClassesIterator : public StackObj {
 private:
  typeArrayHandle _inner_classes;
  int _length;
  int _idx;
 public:

  InnerClassesIterator(instanceKlassHandle k) {
    _inner_classes = k->inner_classes();
    if (k->inner_classes() != NULL) {
      _length = _inner_classes->length();
      // The inner class array's length should be the multiple of
      // inner_class_next_offset if it only contains the InnerClasses
      // attribute data, or it should be
      // n*inner_class_next_offset+enclosing_method_attribute_size
      // if it also contains the EnclosingMethod data.
      assert((_length % instanceKlass::inner_class_next_offset == 0 ||
              _length % instanceKlass::inner_class_next_offset == instanceKlass::enclosing_method_attribute_size),
             "just checking");
      // Remove the enclosing_method portion if exists.
      if (_length % instanceKlass::inner_class_next_offset == instanceKlass::enclosing_method_attribute_size) {
        _length -= instanceKlass::enclosing_method_attribute_size;
      }
    } else {
      _length = 0;
    }
    _idx = 0;
  }

  int length() const {
    return _length;
  }

  void next() {
    _idx += instanceKlass::inner_class_next_offset;
  }

  bool done() const {
    return (_idx >= _length);
  }

  u2 inner_class_info_index() const {
    return _inner_classes->ushort_at(
               _idx + instanceKlass::inner_class_inner_class_info_offset);
  }

  void set_inner_class_info_index(u2 index) {
    _inner_classes->ushort_at_put(
               _idx + instanceKlass::inner_class_inner_class_info_offset, index);
  }

  u2 outer_class_info_index() const {
    return _inner_classes->ushort_at(
               _idx + instanceKlass::inner_class_outer_class_info_offset);
  }

  void set_outer_class_info_index(u2 index) {
    _inner_classes->ushort_at_put(
               _idx + instanceKlass::inner_class_outer_class_info_offset, index);
  }

  u2 inner_name_index() const {
    return _inner_classes->ushort_at(
               _idx + instanceKlass::inner_class_inner_name_offset);
  }

  void set_inner_name_index(u2 index) {
    _inner_classes->ushort_at_put(
               _idx + instanceKlass::inner_class_inner_name_offset, index);
  }

  u2 inner_access_flags() const {
    return _inner_classes->ushort_at(
               _idx + instanceKlass::inner_class_access_flags_offset);
  }
};

#endif // SHARE_VM_OOPS_INSTANCEKLASS_HPP
