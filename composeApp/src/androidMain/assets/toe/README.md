<!-- Copyright (c) 2025-2026 Jacob See. Split licensing: this repository mixes Apache-2.0 science materials with all-rights-reserved product materials — see LICENSE and docs/LICENSING.md. -->

# Omega Theory Everything

## Integrated systems blueprint

The repository now includes [`tri_token_sovereign_economy_blueprint.md`](tri_token_sovereign_economy_blueprint.md), a research-and-systems specification for a proposed Tri-Token Sovereign Economy. It connects the Omega research program to Proof of Useful Work, C.A.R.E. privacy and arbitration, active-inference simulation, telemetry safeguards, asymmetric AMM/FINN clearing, governance, and staged implementation.

The blueprint is intentionally explicit about what is hypothetical, what requires independent validation, and what must not be used for automatic financial, medical, or public-benefit decisions.

The expanded whitepaper set is in [`whitepapers/`](whitepapers/): $OMEGA, legacy TOKAMAK domain research (not the Solana token identity), C.A.R.E./AMITY, and Lucifer–Hermes Omni-Bridge Prime.

The Omega MCP Hub — a sovereign MCP (Model Context Protocol) server exposing 22 tools across the sov/use/care/amity/omega planes over stdio JSON-RPC — is in [`mcp/`](mcp/) (see [`mcp/README_MCP_HUB.md`](mcp/README_MCP_HUB.md); its smoke-test evidence is committed alongside it).

The Omni-Bridge control-boundary implementation — the Hermes ⇄ Lucifer decision layer (APPA capability algebra, human-governed capabilities, hash-chained evidence ledger, T0–T5 sandbox tier selection), header-only C++23 with its acceptance suite — is in [`omni-bridge/`](omni-bridge/); implementation status, test evidence, and documented deviations are in [`omni-bridge/README.md`](omni-bridge/README.md). The CBwK shadow-price pacer (Lucifer–Hermes routing governor) is in [`cpp/`](cpp/).

A wallet-first web prototype is in [`app/`](app/). It performs real client-side wallet flows — BIP-39 mnemonic creation and import, MetaMask connection, an encrypted local keystore — with keys never leaving the device; balance lookups use public Ethereum RPC endpoints (read-only). The site runs no backend of its own and collects nothing.

The day-one launch plan for the token-gated release of the novel **Crucible: The Satoshi Protocol** under the C.A.R.E. Protocol ($OMEGA on Ethereum, **Token of the World Citizen (TWC)** on Solana, AMITY on Bitcoin Lightning via Taproot Assets) is in [`launch/novel_day_one_plan.md`](launch/novel_day_one_plan.md). The Ethereum leg has a **Sepolia-only, valueless pilot suite** in [`evm/`](evm/): a fixed-supply `tOMEGA`, non-transferable vote escrow, bounded locking, OpenZeppelin Governor + TimelockController, and a claim-receipt gate. The Solana leg has a **Devnet-only, valueless `tTWC` pilot issuer/verifier** in [`solana/`](solana/): a standard fixed-supply SPL mint, null mint/freeze authorities, immutable Metaplex fungible metadata, and offline transaction-plan tests. Neither pilot has been deployed, independently audited, or approved for mainnet/value-bearing use. The reading experience is implemented in [`web/`](web/): a Next.js site serving chapters through a signature-verified unlock gate (EIP-191 `personal_sign`; the server recovers the signer address from the signature itself, never trusting a client-supplied address). **Open release decision:** the manuscript the web app reads, `web/public/book/full-book.md`, is currently committed in plaintext, while the sealed-staging tooling in [`novel/`](novel/) (`seal.sh`; ciphertext + SHA-256 commitment) is not yet applied — sealing the book or accepting the public plaintext must be decided before launch day.

An RCOD multi-scale optimizer-governor research prototype (Omega informational-geometry metrics as optimizer governors) with its label-noise-recovery benchmark is in [`rcod/`](rcod/) — the benchmark's honest verdict, including a negative result for the spec-as-written thresholds, is in [`rcod/RESULTS.md`](rcod/RESULTS.md).


Complete implementation of the Omega Theory framework - a unified physics model deriving spacetime, gravity, and cosmology from quantum information principles.

## Overview

This repository contains the full simulation suite for Omega Theory v4.0, implementing:

- **Sim1**: Emergent Geometry from quantum entanglement
- **Sim2**: Cosmological dynamics with dynamic scale factor
- **Sim3**: Renormalization group flow in spacetime
- **Sim4**: System evolution with v14 depletion mechanics
- **Sim5**: Emergent gravity as entropic force
- **Sim6**: v14 depletion dynamics

## Theory Foundation

Based on the principle that spacetime geometry emerges from quantum entanglement entropy:

```
S_ent = A/4G  →  g_μν = δS_ent/δρ
```

Key papers:
- `Omega_Theory_v4.0_Technical.md` - Full technical specification
- `Omega_Theory_Laymans_Guide.md` - Accessible overview

## Formal Verification (Lean 4)

The complete mathematical framework is formally verified in Lean 4:

```
lean_proofs/
├── ToE.lean                          # Main entry point
├── OmegaAxioms.lean                  # Foundational axioms
├── OmegaUnifiedFoundation.lean       # Unified foundation
├── OmegaDimensionalHierarchy.lean    # Dimensional hierarchy
├── OmegaProtocol.lean                # Cross-volume theorems
├── Vol01_ClassicalMechanics.lean     # through
└── Vol54_TheoryOfNothing.lean        # 54 physics volumes
```

Each Lean proof has companion files:
- **LaTeX**: `latex_docs/Vol##_*.tex` - Mathematical typesetting
- **Text**: `txt_proofs/Vol##_*.txt` - Plain text for accessibility

## Structure

```
Omega_Theory_Everything/
├── lean_proofs/              # 58 Lean 4 formalizations
│   ├── ToE.lean
│   ├── OmegaAxioms.lean
│   ├── OmegaUnifiedFoundation.lean
│   ├── OmegaDimensionalHierarchy.lean
│   ├── OmegaProtocol.lean
│   ├── Vol01_ClassicalMechanics.lean
│   └── ... (through Vol54)
├── latex_docs/               # 46 LaTeX documents (Vol09-Vol54)
│   ├── Vol09_HolographicPrinciple.tex
│   └── ... (through Vol54)
├── txt_proofs/               # 104 plain text companions
│   ├── Vol01_ClassicalMechanics.txt
│   └── ... (through Vol54, both Lean + LaTeX)
├── Sim1_Emergent_Geometry.py
├── Sim2_Cosmology.py
├── Sim3_Dynamic_Scale.py
├── Sim4_Evolution.py
├── Sim5_Emergent_Gravity.py
├── sim6_v14_depletion.py
├── rcod/                     # RCOD optimizer governor (research prototype + benchmark)
├── cpp/                      # CBwK shadow-price pacer (Lucifer–Hermes routing governor, C++23)
├── omni-bridge/              # Omni-Bridge control boundary (Hermes ⇄ Lucifer), C++23 + host audit kit
├── mcp/                      # Omega MCP Hub server (sov/use/care/amity/omega planes, stdio JSON-RPC)
├── evm/                      # Sepolia-only, valueless tOMEGA governance + gate pilot
├── solana/                   # Devnet-only, valueless tTWC SPL mint issuer + verifier
├── web/                      # Next.js site: tri-token economy + token-gated novel chapters
├── novel/                    # Novel release plan + sealed-manuscript staging
├── launch/                   # Day-one launch plan documents
├── update_discovery.sh
├── requirements.txt
└── *.md
```

## Quick Start

```bash
# Install Python dependencies
pip install -r requirements.txt

# Run simulations
python Sim1_Emergent_Geometry.py
python Sim2_Cosmology.py
# ... etc

# Build Lean 4 proofs (requires Lean 4 + Mathlib4)
cd lean_proofs && lake build
```

## Development

```bash
# Python linting
ruff check .
mypy .

# Lean 4 build
cd lean_proofs && lake build

# Test
pytest -v
```

## License and intellectual property

This repository uses a **split license**: the science is open; the product materials are proprietary.

| Materials | Terms |
|---|---|
| Simulations, Omega theory notes, Lean proofs, LaTeX and text companions, and listed tooling | Apache-2.0 — see [`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt) |
| `app/`, `rust/`, `evm/`, `solana/`, [`whitepapers/`](whitepapers/), and the [Tri-Token blueprint](tri_token_sovereign_economy_blueprint.md) | All rights reserved. Commercial licenses may be negotiated under a separate signed agreement with a percentage-based royalty; no rate or commercial permission is set by this repository. |

See [`LICENSE`](LICENSE) for exact path scope and [`docs/LICENSING.md`](docs/LICENSING.md) for important limits, including the prior MIT license and public-disclosure history. Copyright does not generally make underlying ideas, methods, or systems exclusive. Patent status is not claimed; see [`docs/PATENT-POSTURE.md`](docs/PATENT-POSTURE.md). Project-name notices are in [`docs/TRADEMARKS.md`](docs/TRADEMARKS.md).
