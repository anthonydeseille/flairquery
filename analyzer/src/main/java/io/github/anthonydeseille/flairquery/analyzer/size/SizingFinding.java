package io.github.anthonydeseille.flairquery.analyzer.size;

import java.util.List;
import java.util.Map;

/**
 * One infrastructure sizing finding (phase 6), deterministic: computed from the collector's measurements only.
 *
 * @param rule        {@link Sizer.Rule} name
 * @param target      what to change: "Deployment/demo-app/demo-app" (workload/container) or "postgres:&lt;workload&gt;"
 * @param evidence    the measurements the finding rests on (window, values)
 * @param current     the configuration in place
 * @param recommended the configuration proposed, to be proven on a replay (step 3) before any merge request
 * @param rationale   how the recommendation is computed from the evidence
 * @param notes       limits of the recommendation (what it cannot cover)
 */
public record SizingFinding(String id, String rule, String severity, String target, String title,
                            Map<String, Object> evidence, Map<String, Object> current, Map<String, Object> recommended,
                            String rationale, List<String> notes) { }
