#
# Copyright (c) 2004, 2011, Oracle and/or its affiliates. All rights reserved.
# Copyright 2012, 2013 SAP AG. All rights reserved.
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

# Produce 64 bits object files.
CFLAGS += -q64

# Make c code know it is on a 64 bit platform. 
# xlc: implicitly set by -q64

# Balanced tuning for recent versions of the POWER architecture (if supported by xlc).
QTUNE=$(if $(CPP_SUPPORTS_BALANCED_TUNING),balanced,pwr5)

# Try to speed up the interpreter: use ppc64 instructions and inline 
# glue code for external functions.
OPT_CFLAGS += -qarch=ppc64 -qtune=$(QTUNE) -qinlglue

# We need variable length arrays
CFLAGS += -qlanglvl=c99vla
# Just to check for unwanted macro redefinitions
CFLAGS += -qlanglvl=noredefmac

# Surpress those "implicit private" warnings xlc gives.
#  - The omitted keyword "private" is assumed for base class "...".
CFLAGS += -qsuppress=1540-0198

# Surpress the following numerous warning:
#  - 1540-1090 (I) The destructor of "..." might not be called.
#  - 1500-010: (W) WARNING in ...: Infinite loop.  Program may not stop.
#    There are several infinite loops in the vm, suppress.
CFLAGS += -qsuppress=1540-1090 -qsuppress=1500-010

# Turn off floating-point optimizations that may alter program semantics
OPT_CFLAGS += -qstrict

# Disable aggressive optimizations for functions in sharedRuntimeTrig.cpp 
# and sharedRuntimeTrans.cpp on ppc64. 
# -qstrict turns off the following optimizations:
#   * Performing code motion and scheduling on computations such as loads
#     and floating-point computations that may trigger an exception.
#   * Relaxing conformance to IEEE rules.
#   * Reassociating floating-point expressions.
# When using '-qstrict' there still remains one problem
# in javasoft.sqe.tests.api.java.lang.Math.sin5Tests when run in compile-all
# mode, so don't optimize sharedRuntimeTrig.cpp at all.
OPT_CFLAGS/sharedRuntimeTrig.o = $(OPT_CFLAGS/NOOPT)
OPT_CFLAGS/sharedRuntimeTrans.o = $(OPT_CFLAGS/NOOPT)

# Xlc V8 generates bad code for frame.cpp if -qipa is not present.
QIPA_COMPILE = -qipa

# Xlc 10.1 parameters for aggressive optimization:
# - qhot=level=1: Most aggressive loop optimizations.
# - qignerrno: Assume errno is not modified by system calls.
# - qinline: Inline method calls. No suboptions for c++ compiles.
# - qxflag=ASMMIDCOALFIX: Activate fix for -O3 problem in interpreter loop.
# - qxflag=asmfastsync: Activate fix for performance problem with inline assembler with memory clobber.
QV10_OPT=$(if $(CPP_IS_V10),-qxflag=ASMMIDCOALFIX -qxflag=asmfastsync)
QV10_OPT_AGGRESSIVE=$(if $(CPP_IS_V10),-qhot=level=1 -qignerrno -qinline)
QV10_OPT_CONSERVATIVE=$(if $(CPP_IS_V10),-qhot=level=1 -qignerrno -qinline)

# Disallow inlining for synchronizer.cpp, but perform O3 optimizations.
OPT_CFLAGS/synchronizer.o = $(OPT_CFLAGS) -qnoinline

# Set all the xlC V10.1 options here.
OPT_CFLAGS += $(QIPA_COMPILE) $(QV10_OPT) $(QV10_OPT_AGGRESSIVE)

export OBJECT_MODE=64

# PPC usees safefetch stubs.
CFLAGS += -DSAFEFETCH_STUBS

# Let linker produce 64 bit lib.
#LFLAGS_VM += -m64

# Let linker find external 64 bit libs.
#LFLAGS_VM += -L/lib64

# Specify lib format.
#LFLAGS_VM +=  -Wl,-melf64ppc

# Also build launcher as 64 bit executable.
LAUNCHERFLAGS += -q64
#LAUNCHERFLAGS += -D_LP64=1 implicitly set by -q64
#AOUT_FLAGS += -q64
#AOUT_FLAGS += -L/lib64
#AOUT_FLAGS += -Wl,-melf64ppc
