package com.turngame;

import com.turngame.engine.GameSession;
import com.turngame.engine.TurnManager;
import com.turngame.engine.command.AttackAction;
import com.turngame.engine.command.EndTurnAction;
import com.turngame.engine.rules.BasicRuleSet;
import com.turngame.event.EventBus;
import com.turngame.event.GameEndedEvent;
import com.turngame.event.TurnStartedEvent;

import java.util.List;

public class App {
    public static void main(String[] args) {
        EventBus eventBus = new EventBus();
        eventBus.subscribe(TurnStartedEvent.class, e -> System.out.println("Turn started: " + e.playerId()));
        eventBus.subscribe(GameEndedEvent.class, e -> System.out.println("Winner: " + e.winnerId()));

        TurnManager turnManager = new TurnManager(List.of("p1", "p2"));
        GameSession session = new GameSession("m-1", new BasicRuleSet(), turnManager, eventBus);
        session.addPlayer("p1", 100);
        session.addPlayer("p2", 100);

        System.out.println("Current turn: " + session.getCurrentPlayerId());
        session.submitAction(new AttackAction("p1", "p2", 30));
        session.submitAction(new EndTurnAction("p1"));
        System.out.println("Current turn: " + session.getCurrentPlayerId());
    }
}
