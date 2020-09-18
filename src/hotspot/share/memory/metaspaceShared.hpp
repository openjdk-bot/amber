/*
 * Copyright (c) 2012, 2020, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_MEMORY_METASPACESHARED_HPP
#define SHARE_MEMORY_METASPACESHARED_HPP

#include "classfile/compactHashtable.hpp"
#include "memory/allocation.hpp"
#include "memory/memRegion.hpp"
#include "memory/virtualspace.hpp"
#include "oops/oop.hpp"
#include "utilities/macros.hpp"
#include "utilities/resourceHash.hpp"

#define MAX_SHARED_DELTA                (0x7FFFFFFF)

class outputStream;
class CHeapBitMap;
class FileMapInfo;
class DumpRegion;
struct ArchiveHeapOopmapInfo;

enum MapArchiveResult {
  MAP_ARCHIVE_SUCCESS,
  MAP_ARCHIVE_MMAP_FAILURE,
  MAP_ARCHIVE_OTHER_FAILURE
};

class MetaspaceSharedStats {
public:
  MetaspaceSharedStats() {
    memset(this, 0, sizeof(*this));
  }
  CompactHashtableStats symbol;
  CompactHashtableStats string;
};

// Class Data Sharing Support
class MetaspaceShared : AllStatic {

  // CDS support

  // Note: _shared_rs and _symbol_rs are only used at dump time.
  static ReservedSpace _shared_rs;
  static VirtualSpace _shared_vs;
  static ReservedSpace _symbol_rs;
  static VirtualSpace _symbol_vs;
  static int _max_alignment;
  static MetaspaceSharedStats _stats;
  static bool _has_error_classes;
  static bool _archive_loading_failed;
  static bool _remapped_readwrite;
  static address _i2i_entry_code_buffers;
  static size_t  _i2i_entry_code_buffers_size;
  static size_t  _core_spaces_size;
  static void* _shared_metaspace_static_top;
  static intx _relocation_delta;
  static char* _requested_base_address;
  static bool _use_optimized_module_handling;
  static bool _use_full_module_graph;
 public:
  enum {
    // core archive spaces
    mc = 0,  // miscellaneous code for method trampolines
    rw = 1,  // read-write shared space in the heap
    ro = 2,  // read-only shared space in the heap
    bm = 3,  // relocation bitmaps (freed after file mapping is finished)
    num_core_region = 3,
    num_non_heap_spaces = 4,

    // mapped java heap regions
    first_closed_archive_heap_region = bm + 1,
    max_closed_archive_heap_region = 2,
    last_closed_archive_heap_region = first_closed_archive_heap_region + max_closed_archive_heap_region - 1,
    first_open_archive_heap_region = last_closed_archive_heap_region + 1,
    max_open_archive_heap_region = 2,
    last_open_archive_heap_region = first_open_archive_heap_region + max_open_archive_heap_region - 1,

    last_valid_region = last_open_archive_heap_region,
    n_regions =  last_valid_region + 1 // total number of regions
  };

  static void prepare_for_dumping() NOT_CDS_RETURN;
  static void preload_and_dump(TRAPS) NOT_CDS_RETURN;
  static int preload_classes(const char * class_list_path,
                             TRAPS) NOT_CDS_RETURN_(0);

  static GrowableArray<Klass*>* collected_klasses();

  static ReservedSpace* shared_rs() {
    CDS_ONLY(return &_shared_rs);
    NOT_CDS(return NULL);
  }

  static Symbol* symbol_rs_base() {
    return (Symbol*)_symbol_rs.base();
  }

  static void set_shared_rs(ReservedSpace rs) {
    CDS_ONLY(_shared_rs = rs);
  }

  static void commit_to(ReservedSpace* rs, VirtualSpace* vs, char* newtop) NOT_CDS_RETURN;
  static void initialize_dumptime_shared_and_meta_spaces() NOT_CDS_RETURN;
  static void initialize_runtime_shared_and_meta_spaces() NOT_CDS_RETURN;
  static void post_initialize(TRAPS) NOT_CDS_RETURN;

  static void print_on(outputStream* st);

  // Delta of this object from SharedBaseAddress
  static uintx object_delta_uintx(void* obj);

  static u4 object_delta_u4(void* obj) {
    // offset is guaranteed to be less than MAX_SHARED_DELTA in DumpRegion::expand_top_to()
    uintx deltax = object_delta_uintx(obj);
    guarantee(deltax <= MAX_SHARED_DELTA, "must be 32-bit offset");
    return (u4)deltax;
  }

  static void set_archive_loading_failed() {
    _archive_loading_failed = true;
  }
  static bool is_in_output_space(void* ptr) {
    assert(DumpSharedSpaces, "must be");
    return shared_rs()->contains(ptr);
  }

  static bool map_shared_spaces(FileMapInfo* mapinfo) NOT_CDS_RETURN_(false);
  static void initialize_shared_spaces() NOT_CDS_RETURN;

  // Return true if given address is in the shared metaspace regions (i.e., excluding any
  // mapped shared heap regions.)
  static bool is_in_shared_metaspace(const void* p) {
    return MetaspaceObj::is_shared((const MetaspaceObj*)p);
  }

  static address shared_metaspace_top() {
    return (address)MetaspaceObj::shared_metaspace_top();
  }

  static void set_shared_metaspace_range(void* base, void *static_top, void* top) NOT_CDS_RETURN;

  // Return true if given address is in the shared region corresponding to the idx
  static bool is_in_shared_region(const void* p, int idx) NOT_CDS_RETURN_(false);

  static bool is_in_trampoline_frame(address addr) NOT_CDS_RETURN_(false);

  static bool is_shared_dynamic(void* p) NOT_CDS_RETURN_(false);

  static void serialize(SerializeClosure* sc) NOT_CDS_RETURN;

  static MetaspaceSharedStats* stats() {
    return &_stats;
  }

  static void report_out_of_space(const char* name, size_t needed_bytes);

  // JVM/TI RedefineClasses() support:
  // Remap the shared readonly space to shared readwrite, private if
  // sharing is enabled. Simply returns true if sharing is not enabled
  // or if the remapping has already been done by a prior call.
  static bool remap_shared_readonly_as_readwrite() NOT_CDS_RETURN_(true);
  static bool remapped_readwrite() {
    CDS_ONLY(return _remapped_readwrite);
    NOT_CDS(return false);
  }

  static bool try_link_class(InstanceKlass* ik, TRAPS);
  static void link_and_cleanup_shared_classes(TRAPS) NOT_CDS_RETURN;
  static bool link_class_for_cds(InstanceKlass* ik, TRAPS) NOT_CDS_RETURN_(false);
  static bool linking_required(InstanceKlass* ik) NOT_CDS_RETURN_(false);

#if INCLUDE_CDS
  static size_t reserved_space_alignment();
  static void init_shared_dump_space(DumpRegion* first_space);
  static DumpRegion* misc_code_dump_space();
  static DumpRegion* read_write_dump_space();
  static DumpRegion* read_only_dump_space();
  static void pack_dump_space(DumpRegion* current, DumpRegion* next,
                              ReservedSpace* rs);

  static void rewrite_nofast_bytecodes_and_calculate_fingerprints(Thread* thread, InstanceKlass* ik);
#endif

  // Allocate a block of memory from the temporary "symbol" region.
  static char* symbol_space_alloc(size_t num_bytes);

  // Allocate a block of memory from the "mc" or "ro" regions.
  static char* misc_code_space_alloc(size_t num_bytes);
  static char* read_only_space_alloc(size_t num_bytes);
  static char* read_write_space_alloc(size_t num_bytes);

  template <typename T>
  static Array<T>* new_ro_array(int length) {
    size_t byte_size = Array<T>::byte_sizeof(length, sizeof(T));
    Array<T>* array = (Array<T>*)read_only_space_alloc(byte_size);
    array->initialize(length);
    return array;
  }

  template <typename T>
  static Array<T>* new_rw_array(int length) {
    size_t byte_size = Array<T>::byte_sizeof(length, sizeof(T));
    Array<T>* array = (Array<T>*)read_write_space_alloc(byte_size);
    array->initialize(length);
    return array;
  }

  template <typename T>
  static size_t ro_array_bytesize(int length) {
    size_t byte_size = Array<T>::byte_sizeof(length, sizeof(T));
    return align_up(byte_size, BytesPerWord);
  }

  static address i2i_entry_code_buffers(size_t total_size);

  static address i2i_entry_code_buffers() {
    return _i2i_entry_code_buffers;
  }
  static size_t i2i_entry_code_buffers_size() {
    return _i2i_entry_code_buffers_size;
  }
  static void relocate_klass_ptr(oop o);

  static Klass* get_relocated_klass(Klass *k, bool is_final=false);

  static void initialize_ptr_marker(CHeapBitMap* ptrmap);

  // This is the base address as specified by -XX:SharedBaseAddress during -Xshare:dump.
  // Both the base/top archives are written using this as their base address.
  static char* requested_base_address() {
    return _requested_base_address;
  }

  // Non-zero if the archive(s) need to be mapped a non-default location due to ASLR.
  static intx relocation_delta() { return _relocation_delta; }
  static intx final_delta();
  static bool use_windows_memory_mapping() {
    const bool is_windows = (NOT_WINDOWS(false) WINDOWS_ONLY(true));
    //const bool is_windows = true; // enable this to allow testing the windows mmap semantics on Linux, etc.
    return is_windows;
  }

  static void write_core_archive_regions(FileMapInfo* mapinfo,
                                         GrowableArray<ArchiveHeapOopmapInfo>* closed_oopmaps,
                                         GrowableArray<ArchiveHeapOopmapInfo>* open_oopmaps);

  // Can we skip some expensive operations related to modules?
  static bool use_optimized_module_handling() { return NOT_CDS(false) CDS_ONLY(_use_optimized_module_handling); }
  static void disable_optimized_module_handling() { _use_optimized_module_handling = false; }

  // Can we use the full archived modue graph?
  static bool use_full_module_graph() NOT_CDS_RETURN_(false);
  static void disable_full_module_graph() { _use_full_module_graph = false; }

private:
#if INCLUDE_CDS
  static void write_region(FileMapInfo* mapinfo, int region_idx, DumpRegion* dump_region,
                           bool read_only,  bool allow_exec);
#endif
  static void read_extra_data(const char* filename, TRAPS) NOT_CDS_RETURN;
  static FileMapInfo* open_static_archive();
  static FileMapInfo* open_dynamic_archive();
  // use_requested_addr: If true (default), attempt to map at the address the
  static MapArchiveResult map_archives(FileMapInfo* static_mapinfo, FileMapInfo* dynamic_mapinfo,
                                       bool use_requested_addr);
  static char* reserve_address_space_for_archives(FileMapInfo* static_mapinfo,
                                                  FileMapInfo* dynamic_mapinfo,
                                                  bool use_archive_base_addr,
                                                  ReservedSpace& archive_space_rs,
                                                  ReservedSpace& class_space_rs);
  static void release_reserved_spaces(ReservedSpace& archive_space_rs,
                                      ReservedSpace& class_space_rs);
  static MapArchiveResult map_archive(FileMapInfo* mapinfo, char* mapped_base_address, ReservedSpace rs);
  static void unmap_archive(FileMapInfo* mapinfo);
};
#endif // SHARE_MEMORY_METASPACESHARED_HPP
