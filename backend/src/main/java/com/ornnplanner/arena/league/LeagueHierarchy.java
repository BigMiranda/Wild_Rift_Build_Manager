package com.ornnplanner.arena.league;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.AutoBrain;
import com.ornnplanner.arena.ai.Network;
import com.ornnplanner.arena.ai.PolicyRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Rodada 21, part 3: the hierarchy class -> champion. The class AIs are trained in the league first, with the champions
 * of the class in turn: they are everyone's base. A champion's AI is a fine-tuning of its class AI in the same league,
 * with its own kit: cheap, since it starts from a network that already fights. It must beat its class AI on the
 * champion's own validation to be accepted, and the automatic brain uses it only then ({@link #championOverClass}).
 */
@Service
public class LeagueHierarchy {

    private final LeagueService league;
    private final LeagueTrainer trainer;
    private final PolicyRepository policies;

    public LeagueHierarchy(LeagueService league, LeagueTrainer trainer, PolicyRepository policies) {
        this.league = league;
        this.trainer = trainer;
        this.policies = policies;
    }

    /**
     * The AI an owner uses now: its newest accepted league version, else its newest accepted one of any kind (the AIs
     * of before the league). Null = none.
     */
    public PolicyRepository.Record inUse(String owner) {
        PolicyRepository.Record league = null;
        PolicyRepository.Record any = null;
        for (PolicyRepository.Record r : policies.list(owner)) {
            if (!r.accepted || !r.compatible() || !r.champion.equalsIgnoreCase(owner)) {
                continue;
            }
            if ("liga".equals(r.objective) && (league == null || r.version > league.version)) {
                league = r;
            }
            if (any == null || r.version > any.version) {
                any = r;
            }
        }
        return league != null ? league : any;
    }

    /** The class AI of a champion's main class in use (null = none). */
    public PolicyRepository.Record classOf(String champion) {
        String cls = league.mainClass(champion);
        return cls == null ? null : inUse(AutoBrain.CLASS_PREFIX + cls);
    }

    /** A saved AI as the player's brain (its own weights on its base, no combos, the style kept). */
    public Function<Arena.Input, Arena.Input> brain(PolicyRepository.Record r) {
        PolicyRepository.Record full = policies.find(r.id).orElseThrow();
        return in -> LeagueTrainer.playing(in, full.policy(in.priority));
    }

    /** Trains a class AI in the league: the class's champions in turn, against the class AI in use. */
    public LeagueTrainer.Result trainClass(String cls, LeagueTrainer.Budget budget, long seed, int threads, LeagueTrainer.Listener l) {
        String owner = AutoBrain.CLASS_PREFIX + cls;
        LeagueTrainer.Request q = new LeagueTrainer.Request();
        q.owner = owner;
        q.players = league.ofClass(cls);
        if (q.players.isEmpty()) {
            throw new IllegalStateException("A classe " + cls + " não tem campeões com ficha.");
        }
        q.budget = budget;
        q.seed = seed;
        q.threads = threads;
        PolicyRepository.Record now = inUse(owner);
        if (now != null) {
            q.incumbent = brain(now);
            q.incumbentId = now.id;
            q.incumbentLabel = owner;
            // starts from the class AI in use when it is better than the tactical rules on the bank ("melhor"), from it
            // always ("atual"), or from zero weights, the tactical rules ("zero")
            LeagueService.Saved m = league.latest(now.id, "*");
            String rule = trainer.settings().classStart;
            boolean fromIt = "atual".equals(rule) || "melhor".equals(rule) && m != null && m.mean >= m.tacticalMean;
            if (fromIt) {
                PolicyRepository.Record full = policies.find(now.id).orElseThrow();
                q.start = full.weights.network();
                q.parentId = now.id;
                q.startLabel = owner + " v" + now.version;
            }
        }
        return trainer.train(q, l);
    }

    /**
     * Fine-tunes a champion's AI from its class AI (zero weights when its class has none), against the class AI playing
     * the same champion on the champion's own validation.
     */
    public LeagueTrainer.Result trainChampion(String champion, LeagueTrainer.Budget budget, long seed, int threads,
                                              LeagueTrainer.Listener l) {
        LeagueTrainer.Request q = new LeagueTrainer.Request();
        q.owner = champion;
        q.players = List.of(champion);
        q.budget = budget;
        q.seed = seed;
        q.threads = threads;
        PolicyRepository.Record cls = classOf(champion);
        if (cls != null) {
            PolicyRepository.Record full = policies.find(cls.id).orElseThrow();
            q.start = full.weights.network();
            q.parentId = cls.id;
            q.startLabel = cls.champion + " v" + cls.version;
            q.incumbent = brain(cls);
            q.incumbentId = cls.id;
            q.incumbentLabel = cls.champion;
        } else {
            q.start = Network.league();
        }
        return trainer.train(q, l);
    }

    /**
     * Whether a champion's AI may be used over its class AI: it beats it on the champion's own validation. Null when
     * there is nothing to compare (no matrix of either on that champion): the automatic brain then keeps its old order.
     */
    public Boolean championOverClass(PolicyRepository.Record mine, PolicyRepository.Record cls, String champion) {
        if (cls == null) {
            return null;
        }
        LeagueService.Saved c = league.latest(cls.id, champion);
        LeagueService.Saved m = mine == null ? null : league.latest(mine.id, champion);
        if (c == null && m == null) {
            return null;
        }
        if (m == null) {
            return false;   // the class AI is measured on this champion and the champion's is not
        }
        if (c == null) {
            return m.mean > m.tacticalMean;   // measured alone: it must at least beat the tactical rules
        }
        return m.mean > c.mean;
    }

    /** Every validated AI of a champion, best mean first (for the screens). */
    public List<LeagueService.Saved> validated(String champion) {
        List<LeagueService.Saved> out = new ArrayList<>();
        for (LeagueService.Saved s : league.list()) {
            if (champion.equalsIgnoreCase(s.champion)) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparingDouble((LeagueService.Saved s) -> -s.mean));
        return out;
    }
}
