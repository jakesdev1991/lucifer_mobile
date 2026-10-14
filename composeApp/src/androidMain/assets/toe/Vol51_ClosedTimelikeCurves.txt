import Mathlib
import OmegaUnifiedFoundation

namespace OmegaProtocol.Vol51
open OmegaProtocol

axiom History : Type
axiom TimeLoopOperator : History → History

def IsConsistent (h : History) : Prop := TimeLoopOperator h = h

theorem novikov_consistency (h : History) (hc : IsConsistent h) :
  TimeLoopOperator (TimeLoopOperator h) = TimeLoopOperator h := by
  dsimp [IsConsistent] at hc
  rw [hc, hc]

end OmegaProtocol.Vol51
