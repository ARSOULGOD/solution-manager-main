package com.solace.sm.dp.apply.production;

import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.agent.AgentConfigGenerations;
import com.solace.sm.dp.config.ConfigGenerationRows;
import com.solace.sm.dp.config.LadderEvidence;
import com.solace.sm.dp.config.LadderRows;
import com.solace.sm.dp.metrics.collect.RestartObservedGenerations;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * The two journal questions the agent path asks (plan 015 T3.1): the config generation an apply request bound, and the generations
 * a reported file digest can belong to when no byte is held in memory.
 *
 * <p>The candidates are, newest first and without duplicates: the instance's newest generation (what is intended next), the newest
 * generation whose {@code MATERIALIZE} for THIS placement succeeded (what the placement last had written: under {@code manual},
 * where nothing is ever restart-observed, the newest generation is often not it), and the newest restart-observed generation.
 */
public final class ProductionAgentConfigGenerations implements AgentConfigGenerations {

    private final OutcomeService outcomes;
    private final ConfigGenerationRows generations;
    private final RestartObservedGenerations restartObserved;
    private final LadderRows ladder;

    public ProductionAgentConfigGenerations(OutcomeService outcomes, ConfigGenerationRows generations,
            RestartObservedGenerations restartObserved, LadderRows ladder) {
        this.outcomes = outcomes;
        this.generations = generations;
        this.restartObserved = restartObserved;
        this.ladder = ladder;
    }

    @Override
    public OptionalLong generationOfApply(TenantId tenant, ApplyId apply) {
        try {
            Long generation = outcomes.requestOf(tenant, apply).generation();
            return generation == null ? OptionalLong.empty() : OptionalLong.of(generation);
        } catch (RuntimeException notThisTenants) {
            return OptionalLong.empty();                 // another tenant's request, or none: nothing to re-render
        }
    }

    @Override
    public List<Long> candidates(PlacementRef placement) {
        List<Long> out = new ArrayList<>(3);
        generations.latest(placement.tenant(), placement.instance()).ifPresent(g -> add(out, g.generation()));
        for (LadderEvidence.PlacementEvidence evidence : ladder.evidence(placement.tenant(), placement.instance()).livePlacements()) {
            if (evidence.placementId().equals(placement.placement().value()) && evidence.latest() != null
                    && evidence.latest().steps().stream().anyMatch(s -> s.step() == Step.MATERIALIZE
                            && s.latestOutcome() == AttemptOutcome.SUCCEEDED)) {
                add(out, evidence.latest().generation());
            }
        }
        OptionalLong observed = restartObserved.latestRestartObservedGeneration(placement.tenant(), placement.instance());
        if (observed.isPresent()) {
            add(out, observed.getAsLong());
        }
        return out;
    }

    private static void add(List<Long> out, long generation) {
        if (!out.contains(generation)) {
            out.add(generation);
        }
    }
}
