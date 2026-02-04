package com.Chris__.realm_ruler.ui;

public sealed interface TimerAction permits TimerAction.Start, TimerAction.Stop {

    record Start(int seconds) implements TimerAction {}

    record Stop() implements TimerAction {}
}
