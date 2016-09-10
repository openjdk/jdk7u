/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
 * Copyright 2013 SAP AG. All rights reserved.
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

#ifdef COMPILER2

#include "adfiles/ad_ppc_64.hpp"
#include "compile_ppc.hpp"
#include "opto/compile.hpp"
#include "opto/machnode.hpp"
#include "opto/node.hpp"
#include "oops/oop.inline.hpp"

struct pdCompileEnv {
  Compile  *C;
  MachNode *loadPoll;
};

void PdCompile::pd_post_matching_hook(Compile* C) {
  Node *root = (Node*) C->root();
  pdCompileEnv env = { C, NULL };
  root->walk(visit_node_and_connect_toc, Node::nop, (void *)&env);
}

// The ins before the jvms ins were changed.  We added or removed 'change'
// edges.  Adapt the jvms offsets.
static void fix_jvms(JVMState *jvms, int change) {
  jvms->set_locoff(jvms->locoff() + change);
  jvms->set_stkoff(jvms->stkoff() + change);
  jvms->set_monoff(jvms->monoff() + change);
  jvms->set_scloff(jvms->scloff() + change);
  jvms->set_endoff(jvms->endoff() + change);
  if (jvms->caller()) fix_jvms(jvms->caller(), change);
}

// Encoding large constant as immediates requires a lot of instructions
// on PPC. Therefore we load the constants from the constant pool.
//
// To access the constant pool we must know the toc. C2 supplies a special
// mach node MachConstantBaseNode to load the toc, and adlc adds this node
// to constants if specified in the ad file.
// Unfortunately this does not work for storeCM, a store node, and call nodes.
// So we add the MachConstantBaseNode here, just after matching.
void PdCompile::visit_node_and_connect_toc(Node &node, void *env) {
  pdCompileEnv *pdEnv = (pdCompileEnv *)env;

  if (node.is_Mach()) {
    MachNode *m = node.as_Mach();
    if (m->ins_requires_toc() != 0) {
      Node *loadToc = pdEnv->C->mach_constant_base_node();

      // Some call nodes require the toc. We abuse the input 4 (ReturnAdr) which
      // is connected to top for our purpose here.
      if (m->rule() == CallLeafDirect_Ex_rule     || m->rule() == CallLeafNoFPDirect_Ex_rule       ||
          m->rule() == CallDynamicJavaDirect_rule || m->rule() == CallDynamicJavaDirectSched_Ex_rule) {
        assert(m->in(TypeFunc::ReturnAdr)->is_top(), "not top?");
        MachSafePointNode *call = (MachSafePointNode *)m;
        // Set register mask.
        if (m->rule() == CallLeafDirect_Ex_rule || m->rule() == CallLeafNoFPDirect_Ex_rule) {
          call->_in_rms[TypeFunc::ReturnAdr] = BITS64_REG_LEAF_CALL_mask();
        } else {
          call->_in_rms[TypeFunc::ReturnAdr] = BITS64_REG_DYNAMIC_CALL_mask();
        }
        m->set_req(TypeFunc::ReturnAdr, loadToc);
      }
    }
  }
}

#endif // COMPILER2
