import Mathlib
import OmegaUnifiedFoundation
import Vol07_Cosmology

namespace OmegaProtocol.Vol15
open OmegaProtocol
open Vol07

theorem inflation_acceleration 
  (t : CosmologicalTime)
  (h_lambda : CosmologicalConstant = 0)
  (h_inflation : EnergyDensity t + 3 * Pressure t < 0) :
  TimeDerivative (TimeDerivative ScaleFactor) t / ScaleFactor t > 0 := by
  have h_acc := global_state_dynamics t
  rw [h_lambda] at h_acc
  have h_zero : (0 : ℝ) / 3 = 0 := by norm_num
  rw [h_zero] at h_acc
  have h_G : 4 * Real.pi * NewtonG / 3 > 0 := by
    have h1 : (4 : ℝ) > 0 := by norm_num
    have h2 : Real.pi > 0 := Real.pi_pos
    have h3 : NewtonG > 0 := NewtonG_pos
    have h4 : (3 : ℝ) > 0 := by norm_num
    positivity
  
  calc TimeDerivative (TimeDerivative ScaleFactor) t / ScaleFactor t
    _ = - (4 * Real.pi * NewtonG / 3) * (EnergyDensity t + 3 * Pressure t) + 0 := h_acc
    _ = - (4 * Real.pi * NewtonG / 3) * (EnergyDensity t + 3 * Pressure t) := by ring
    _ > 0 := by
      -- a > 0, b < 0 => -a * b > 0
      nlinarith

end OmegaProtocol.Vol15
