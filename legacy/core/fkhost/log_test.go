package fkhost

import "testing"

func TestLoggerRingForwardAndSubscribers(t *testing.T) {
	logger := NewLogger(3)
	var forwarded, subscribed []string
	logger.SetForward(func(entry Entry) { forwarded = append(forwarded, entry.Message) })
	cancel := logger.Subscribe(func(entry Entry) { subscribed = append(subscribed, entry.Level.String()+":"+entry.Message) })
	for _, message := range []string{"a", "b", "c", "d"} {
		logger.Write(LevelInfo, message)
	}
	cancel()
	logger.Write(LevelError, "e")
	recent := logger.Recent()
	if len(recent) != 3 || recent[0].Message != "c" || recent[2].Message != "e" || recent[2].ID != 4 {
		t.Fatalf("ring: %+v", recent)
	}
	if len(forwarded) != 5 || forwarded[4] != "e" {
		t.Fatalf("forward: %v", forwarded)
	}
	if len(subscribed) != 4 || subscribed[3] != "info:d" {
		t.Fatalf("subscribers: %v", subscribed)
	}
	if LevelError.Priority() != 2 || LevelWarn.Priority() != 3 || LevelInfo.Priority() != 4 || LevelDebug.Priority() != 5 {
		t.Fatal("priorities")
	}
}
