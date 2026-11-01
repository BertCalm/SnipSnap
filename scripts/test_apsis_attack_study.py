import unittest
import numpy as np
import apsis_voice_study as a
import apsis_attack_study as b


class AttackStudyTest(unittest.TestCase):
    def test_energy_budget_and_hardness_are_bounded_in_sparse_and_dense_schedules(self):
        for macros in ({}, {'BODIES':1.,'ALIGN':1.,'DISTANCE':0.}, {'BODIES':0.,'DISTANCE':1.}):
            _, r = b.encounter_plan(macros,6.,a.RATE*4)
            self.assertLessEqual(r['spentEnergy'],r['energyUpperBound'])
            for e in r['events']:
                self.assertTrue(0 <= e['energy'] <= e['availableBefore'] <= 1.)
                self.assertTrue(0 <= e['hardness'] <= 1.)

    def test_same_velocity_contacts_have_strength_and_hardness_variation(self):
        _, r = b.encounter_plan({},6.,a.RATE*4)
        amps=[e['amplitude'] for e in r['events']]
        hardness=[e['hardness'] for e in r['events']]
        self.assertGreater(20*np.log10(max(amps)/min(amps)),6.)
        self.assertGreater(max(hardness)-min(hardness),.1)

    def test_periodic_budget_reaches_its_own_boundary_state(self):
        seconds=round(6*.6*(2/.6)**.4*a.RATE)/a.RATE
        _,r=b.encounter_plan({},seconds,a.RATE*4,True)
        self.assertLess(r['periodicBudgetError'],1e-12)

    def test_ringing_survives_retrigger_and_a_reset_mutant_removes_it(self):
        for voice in ('VESSEL','CORONA'):
            live,r=b.render_articulated(voice)
            reset,_=b.render_articulated(voice,reset_on_attack=True)
            self.assertGreater(np.linalg.norm(live-reset)/np.linalg.norm(live),.1)
            self.assertTrue(all(e['energyAlreadyRinging']>0 for e in r['events'][1:]))

    def test_held_output_repeats_after_real_preroll(self):
        for voice in ('VESSEL','CORONA'):
            _,r=b.render_articulated(voice,held=True)
            self.assertLess(r['stateError'],1e-8)
            self.assertLess(r['seamError'],1e-3)
            self.assertLess(r['adjacentCycleRelativeError'],1e-5)

    def test_zero_velocity_and_scope(self):
        for voice in ('VESSEL','CORONA'):
            x,_=b.render_articulated(voice,velocity=0.)
            self.assertFalse(np.any(x))
        for voice in ('GRAIN','ANCHOR','TETHER'):
            with self.assertRaises(ValueError):
                b.render_articulated(voice)


if __name__=='__main__':unittest.main()
