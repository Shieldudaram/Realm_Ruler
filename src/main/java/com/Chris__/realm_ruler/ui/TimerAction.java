package com.Chris__.Realm_Ruler.ui;

public sealed interface TimerAction permits TimerAction.Start, TimerAction.Stop {

    record Start(int seconds) implements TimerAction {}

    record Stop() implements TimerAction {}
}
