package org.mitre.synthea.modules;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mitre.synthea.engine.Components.Range;
import org.mitre.synthea.helpers.SimpleCSV;
import org.mitre.synthea.helpers.TrendingValueGenerator;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.helpers.ValueGenerator;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.BiometricsConfig;
import org.mitre.synthea.world.concepts.HealthRecord;

/**
 * Generate realistic blood pressure vital signs,
 * with impacts from select medications.
 */
public class BloodPressureValueGenerator extends ValueGenerator {
  public enum SysDias {
    SYSTOLIC, DIASTOLIC
  }

  private static final int[] HYPERTENSIVE_SYS_BP_RANGE = BiometricsConfig
      .ints("metabolic.blood_pressure.hypertensive.systolic");
  private static final int[] HYPERTENSIVE_DIA_BP_RANGE = BiometricsConfig
      .ints("metabolic.blood_pressure.hypertensive.diastolic");
  private static final int[] NORMAL_SYS_BP_RANGE = BiometricsConfig
      .ints("metabolic.blood_pressure.normal.systolic");
  private static final int[] NORMAL_DIA_BP_RANGE = BiometricsConfig
      .ints("metabolic.blood_pressure.normal.diastolic");


  public static final Map<String, Range<Double>> HTN_DRUG_IMPACTS;

  static {
    try {
      Map<String, Range<Double>> drugImpacts = new HashMap<>();

      String csv = Utilities.readResource("htn_drugs.csv");

      List<LinkedHashMap<String, String>> table = SimpleCSV.parse(csv);

      for (LinkedHashMap<String,String> line : table) {
        String code = line.get("RxNorm");
        double impactMin = Double.parseDouble(line.get("Impact Minimum"));
        double impactMax = Double.parseDouble(line.get("Impact Maximum"));

        Range<Double> impact = new Range<>();
        impact.low = impactMin;
        impact.high = impactMax;

        drugImpacts.put(code, impact);
      }

      HTN_DRUG_IMPACTS = drugImpacts;
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  // simple 1-value cache, so that we get consistent results when called with the same timestamp
  // note that consistency is not guaranteed if we go time A -> time B -> time A across modules.
  // in that case we'd need a bigger cache
  private Double cachedValue;
  private long cacheTime;

  private SysDias sysDias;

  public BloodPressureValueGenerator(Person person, SysDias sysDias) {
    super(person);
    this.sysDias = sysDias;
  }

  @Override
  public double getValue(long time) {
    if (cacheTime == time && cachedValue != null) {
      return cachedValue;
    }

    double value = calculateMean(person, null, 0, time);

    cachedValue = value
      + getMedicationImpacts(person, time)
      + getLifestyleImpacts(person, value, time);

    cacheTime = time;
    return cachedValue;
  }

  /**
   * Helper function to ensure a person has a consistent impact from a drug.
   */
  private static double getDrugImpact(Person person, long time, String drug,
      Range<Double> impactRange) {
    Map<String, Double> personalDrugImpacts =
        (Map<String, Double>)person.attributes.get("htn_drug_impacts");
    if (personalDrugImpacts == null) {
      personalDrugImpacts = new HashMap<>();
      person.attributes.put("htn_drug_impacts", personalDrugImpacts);
    }

    if (personalDrugImpacts.containsKey(drug)) {
      return personalDrugImpacts.get(drug);
    }

    // note these are intentionally flipped. "max impact" == lower number, since these are negative
    double impact = person.rand(impactRange.high, impactRange.low);

    personalDrugImpacts.put(drug, impact);

    return impact;
  }

  private double calculateMean(Person person, Double startValue, int days, long time) {
    boolean hypertension = (Boolean) person.attributes.getOrDefault("hypertension", false);
    boolean severe = (Boolean) person.attributes.getOrDefault("hypertension_severe", false);

    double baseline;

    String bpBaselineKey = "bp_baseline_" + hypertension + "_" + sysDias.toString();
    if (person.attributes.containsKey(bpBaselineKey)) {
      baseline = (Double)person.attributes.get(bpBaselineKey);
    } else {
      if (sysDias == SysDias.SYSTOLIC) {
        if (hypertension) {
          if (severe) {
            // this leaves fewer people at the upper end of the spectrum
            baseline = person.rand(HYPERTENSIVE_SYS_BP_RANGE[1], HYPERTENSIVE_SYS_BP_RANGE[2]);

          } else {
            // this skews the distribution to be more on the lower side of the range
            baseline = person.rand(HYPERTENSIVE_SYS_BP_RANGE[0], HYPERTENSIVE_SYS_BP_RANGE[1]);
          }
        } else {
          baseline = person.rand(NORMAL_SYS_BP_RANGE);
        }
      } else {
        if (hypertension) {
          baseline = person.rand(HYPERTENSIVE_DIA_BP_RANGE);
        } else {
          baseline = person.rand(NORMAL_DIA_BP_RANGE);
        }
      }

      if ("M".equals(person.attributes.get(Person.GENDER))) {
        baseline += 1;
      } else {
        baseline -= 1;
      }

      person.attributes.put(bpBaselineKey, baseline);
    }
    return baseline;
  }

  private static final long ONE_YEAR = Utilities.convertTime("years", 1);

  private double getLifestyleImpacts(Person person, double baseline, long time) {
    // if the person has a "blood pressure care plan"
    // assume that over ~1 year their blood pressure will be reduced by ~14 mmHg
    // with most of that happening in the first 6 mos

    double maxDrop;
    if (this.sysDias == SysDias.SYSTOLIC) {
      // don't allow diet/exercise alone to drop below 124
      double delta = Math.abs(baseline - 124);
      maxDrop = -Math.min(delta, 14.0);
    } else {
      // don't allow diet/exercise alone to drop below 78
      double delta = Math.abs(baseline - 78);
      maxDrop = -Math.min(delta, 8.0);
    }

    // 443402002 = base hypertension careplan
    // allow for multiple codes, and consider each independently,
    // but if one is active then skip the rest
    // (assume they don't add together)
    String[] carePlanCodes = {"443402002"};

    for (String code : carePlanCodes) {
      double adherenceRatio = 0.15;
      // estimate 3-25% of patients are adherent,
      // so for a single # pick ~15%

      HealthRecord.CarePlan careplan = (HealthRecord.CarePlan) person.record.present.get(code);

      if (careplan != null && careplan.stop == 0L) {
        boolean carePlanAdherent;
        String key = "htn_trial_" + code + "_careplan_adherent";
        if (person.attributes.containsKey(key)) {
          carePlanAdherent = (boolean) person.attributes.get(key);
        } else {
          carePlanAdherent = person.rand() < adherenceRatio;
          person.attributes.put(key, carePlanAdherent);
        }
        if (carePlanAdherent) {
          long start = careplan.start;

          if (time > (start + ONE_YEAR)) {
            // max value
            return maxDrop;
          } else if (time < start) {
            return 0.0;
          }

          // dy / dx = -14/1 = -14
          // y = (dy/dx) * x
          // x = (time - start) / 1 yr

          double x = ((double)(time - start) / ((double)ONE_YEAR));

          return x * maxDrop;
        }
      }
    }

    return 0.0;
  }

  private double getMedicationImpacts(Person person, long time) {
    double drugImpactDelta = 0.0;
    // see also LifecycleModule.calculateVitalSigns

    for (Map.Entry<String, Range<Double>> e : HTN_DRUG_IMPACTS.entrySet()) {
      String medicationCode = e.getKey();
      Range<Double> impactRange = e.getValue();
      if (person.record.medicationActive(medicationCode)) {
        double impact = getDrugImpact(person, time, medicationCode, impactRange);

        // impacts are negative, so add them (ie, don't subtract them)
        drugImpactDelta += impact;
      }
    }

    return drugImpactDelta;
  }
}