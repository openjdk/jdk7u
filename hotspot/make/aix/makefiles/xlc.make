#
# Copyright (c) 1999, 2012, Oracle and/or its affiliates. All rights reserved.
# Copyright (c) 2012 SAP. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#  
#

#------------------------------------------------------------------------
# CC, CXX & AS

# Set compiler explicitly
CXX = $(COMPILER_PATH)xlC_r
CC  = $(COMPILER_PATH)xlc_r
HOSTCXX = $(CXX)
HOSTCC  = $(CC)

AS  = $(CC) -c

# get xlc version
CXX_VERSION   := $(shell $(CXX) -qversion 2>&1 | sed -n 's/.*Version: \([0-9.]*\)/\1/p')

# xlc 08.00.0000.0023 and higher supports -qtune=balanced
CXX_SUPPORTS_BALANCED_TUNING=$(shell if [ $(subst .,,$(CXX_VERSION)) -ge 080000000023 ] ; then echo "true" ; fi)
# xlc 10.01 is used with aggressive optimizations to boost performance
CXX_IS_V10=$(shell if [ $(subst .,,$(CXX_VERSION)) -ge 100100000000 ] ; then echo "true" ; fi)

# check for precompiled headers support

# Switch off the precompiled header support. Neither xlC 8.0 nor xlC 10.0
# support precompiled headers. Both "understand" the command line switches "-qusepcomp" and 
# "-qgenpcomp" but when we specify them the following message is printed:
# "1506-755 (W) The -qusepcomp option is not supported in this release."
USE_PRECOMPILED_HEADER = 0
ifneq ($(USE_PRECOMPILED_HEADER),0)
PRECOMPILED_HEADER_DIR=.
PRECOMPILED_HEADER_SRC=$(GAMMADIR)/src/share/vm/precompiled/precompiled.hpp
PRECOMPILED_HEADER=$(PRECOMPILED_HEADER_DIR)/precompiled.hpp.gch
endif


#------------------------------------------------------------------------
# Compiler flags

# position-independent code
PICFLAG = -qpic=large

VM_PICFLAG/LIBJVM = $(PICFLAG)
VM_PICFLAG/AOUT   =
VM_PICFLAG        = $(VM_PICFLAG/$(LINK_INTO))

CFLAGS += $(VM_PICFLAG)
CFLAGS += -qnortti
CFLAGS += -qnoeh

CFLAGS += -D_REENTRANT
# no xlc counterpart for -fcheck-new
# CFLAGS += -fcheck-new

ARCHFLAG = -q64

CFLAGS     += $(ARCHFLAG)
AOUT_FLAGS += $(ARCHFLAG)
LFLAGS     += $(ARCHFLAG)
ASFLAGS    += $(ARCHFLAG)

# Use C++ Interpreter
ifdef CC_INTERP
  CFLAGS += -DCC_INTERP
endif

# Keep temporary files (.ii, .s)
# no counterpart on xlc for -save-temps, -pipe

# Compiler warnings are treated as errors
# Do not treat warnings as errors
# WARNINGS_ARE_ERRORS = -Werror
# Except for a few acceptable ones
# ACCEPTABLE_WARNINGS = -Wpointer-arith -Wconversion -Wsign-compare
# CFLAGS_WARN/DEFAULT = $(WARNINGS_ARE_ERRORS) $(ACCEPTABLE_WARNINGS)
CFLAGS_WARN/COMMON = 
CFLAGS_WARN/DEFAULT = $(CFLAGS_WARN/COMMON) $(EXTRA_WARNINGS)
# Special cases
CFLAGS_WARN/BYFILE = $(CFLAGS_WARN/$@)$(CFLAGS_WARN/DEFAULT$(CFLAGS_WARN/$@)) 

# The flags to use for an Optimized g++ build
OPT_CFLAGS += -O3

# Hotspot uses very unstrict aliasing turn this optimization off
OPT_CFLAGS += -qalias=noansi

OPT_CFLAGS/NOOPT=-qnoopt

DEPFLAGS = -qmakedep=gcc -MF $(DEP_DIR)/$(@:%=%.d)

#------------------------------------------------------------------------
# Linker flags

# statically link libstdc++.so, work with gcc but ignored by g++
STATIC_STDCXX = -Wl,-lC_r

# statically link libgcc and/or libgcc_s, libgcc does not exist before gcc-3.x.
# Not needed on AIX.
#ifneq ("${CC_VER_MAJOR}", "2")
#STATIC_LIBGCC += -static-libgcc
#endif

ifeq ($(BUILDARCH), ia64)
LFLAGS += -Wl,-relax
endif

# Enable linker optimization
# no counterpart on xlc for this 
# LFLAGS += -Xlinker -O1

# Use $(MAPFLAG:FILENAME=real_file_name) to specify a map file.
# MAPFLAG = -Xlinker --version-script=FILENAME

# Use $(SONAMEFLAG:SONAME=soname) to specify the intrinsic name of a shared obj
# Not needed for xlC_r
#SONAMEFLAG = -Xlinker -soname=SONAME

# Build shared library
SHARED_FLAG = -q64 -b64 -bexpall -G -bnoentry -qmkshrobj -brtl -bnolibpath

#------------------------------------------------------------------------
# Debug flags

# Use the stabs format for debugging information (this is the default
# on gcc-2.91). It's good enough, has all the information about line
# numbers and local variables, and libjvm_g.so is only about 16M.
# Change this back to "-g" if you want the most expressive format.
# (warning: that could easily inflate libjvm_g.so to 150M!)
# Note: The Itanium gcc compiler crashes when using -gstabs.
DEBUG_CFLAGS += -g
ifeq ($(ENABLE_FULL_DEBUG_SYMBOLS),1)
  FASTDEBUG_CFLAGS += -g
  OPT_CFLAGS += -g
endif

# DEBUG_BINARIES overrides everything, use full -g debug information
ifeq ($(DEBUG_BINARIES), true)
  DEBUG_CFLAGS = -g
  CFLAGS += $(DEBUG_CFLAGS)
endif

# If we are building HEADLESS, pass on to VM
# so it can set the java.awt.headless property
ifdef HEADLESS
CFLAGS += -DHEADLESS
endif

# We are building Embedded for a small device
# favor code space over speed
ifdef MINIMIZE_RAM_USAGE
CFLAGS += -DMINIMIZE_RAM_USAGE
endif

ifdef CROSS_COMPILE_ARCH
  STRIP = $(ALT_COMPILER_PATH)/strip
else
  STRIP = strip
endif
