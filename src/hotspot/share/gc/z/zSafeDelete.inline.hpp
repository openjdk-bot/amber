/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_GC_Z_ZSAFEDELETE_INLINE_HPP
#define SHARE_GC_Z_ZSAFEDELETE_INLINE_HPP

#include "gc/z/zSafeDelete.hpp"

#include "gc/z/zArray.inline.hpp"
#include "utilities/debug.hpp"

#include <type_traits>

template <typename T>
ZSafeDeleteImpl<T>::ZSafeDeleteImpl(ZLock* lock) :
    _lock(lock),
    _enabled(0),
    _deferred() {}

template <typename T>
bool ZSafeDeleteImpl<T>::deferred_delete(ItemT* item) {
  ZLocker<ZLock> locker(_lock);
  if (_enabled > 0) {
    _deferred.append(item);
    return true;
  }

  return false;
}

template <typename T>
void ZSafeDeleteImpl<T>::immediate_delete(ItemT* item) {
  if (std::is_array<T>::value) {
    delete [] item;
  } else {
    delete item;
  }
}

template <typename T>
void ZSafeDeleteImpl<T>::enable_deferred_delete() {
  ZLocker<ZLock> locker(_lock);
  _enabled++;
}

template <typename T>
void ZSafeDeleteImpl<T>::disable_deferred_delete() {
  ZArray<ItemT*> deferred;

  {
    ZLocker<ZLock> locker(_lock);
    assert(_enabled > 0, "Invalid state");
    if (--_enabled == 0) {
      deferred.swap(&_deferred);
    }
  }

  ZArrayIterator<ItemT*> iter(&deferred);
  for (ItemT* item; iter.next(&item);) {
    immediate_delete(item);
  }
}

template <typename T>
void ZSafeDeleteImpl<T>::operator()(ItemT* item) {
  if (!deferred_delete(item)) {
    immediate_delete(item);
  }
}

template <typename T>
ZSafeDelete<T>::ZSafeDelete() :
    ZSafeDeleteImpl<T>(&_lock),
    _lock() {}

template <typename T>
ZSafeDeleteNoLock<T>::ZSafeDeleteNoLock() :
    ZSafeDeleteImpl<T>(NULL) {}

#endif // SHARE_GC_Z_ZSAFEDELETE_INLINE_HPP
