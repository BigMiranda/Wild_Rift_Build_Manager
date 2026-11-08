package com.ornnplanner.arena.rift;

/**
 * One macro per side: the match asks this one, and it hands each seat to the macro of its team. Lets a learned macro
 * play one side against the scripted one on the other, with no change to {@link Match}.
 */
public final class SeatMacro implements MacroPolicy {

    private final MacroPolicy[] byTeam;

    public SeatMacro(MacroPolicy blue, MacroPolicy red) {
        this.byTeam = new MacroPolicy[] {blue, red};
    }

    public MacroPolicy of(int team) {
        return byTeam[team];
    }

    @Override
    public Intent decide(Match match, Match.Seat me) {
        return byTeam[me.team].decide(match, me);
    }
}
