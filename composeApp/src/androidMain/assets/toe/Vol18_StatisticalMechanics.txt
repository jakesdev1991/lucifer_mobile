import Mathlib
import OmegaUnifiedFoundation

namespace OmegaProtocol.Vol18
open OmegaProtocol

noncomputable def BoltzmannWeight (beta E : ℝ) : ℝ :=
  Real.exp (-beta * E)

noncomputable def PartitionFunction2 (beta E1 E2 : ℝ) : ℝ :=
  BoltzmannWeight beta E1 + BoltzmannWeight beta E2

noncomputable def ProbState1 (beta E1 E2 : ℝ) : ℝ :=
  BoltzmannWeight beta E1 / PartitionFunction2 beta E1 E2

noncomputable def ProbState2 (beta E1 E2 : ℝ) : ℝ :=
  BoltzmannWeight beta E2 / PartitionFunction2 beta E1 E2

theorem total_probability_is_one 
  (beta E1 E2 : ℝ) 
  (h_Z_pos : PartitionFunction2 beta E1 E2 ≠ 0) :
  ProbState1 beta E1 E2 + ProbState2 beta E1 E2 = 1 := by
  dsimp [ProbState1, ProbState2, PartitionFunction2]
  have h_add : BoltzmannWeight beta E1 / (BoltzmannWeight beta E1 + BoltzmannWeight beta E2) + 
               BoltzmannWeight beta E2 / (BoltzmannWeight beta E1 + BoltzmannWeight beta E2) = 
               (BoltzmannWeight beta E1 + BoltzmannWeight beta E2) / (BoltzmannWeight beta E1 + BoltzmannWeight beta E2) := by
    exact (add_div (BoltzmannWeight beta E1) (BoltzmannWeight beta E2) (BoltzmannWeight beta E1 + BoltzmannWeight beta E2)).symm
  rw [h_add]
  exact div_self h_Z_pos

end OmegaProtocol.Vol18
