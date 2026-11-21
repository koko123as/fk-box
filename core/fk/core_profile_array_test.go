package fk

import (
	"encoding/json"
	"reflect"
	"testing"
)

func testProfileArrayNames(t *testing.T, source string, remarks ...string) []string {
	t.Helper()
	input, _ := json.Marshal(remarks)
	output, err := ProfileArrayNames(source, string(input))
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	if err = json.Unmarshal([]byte(output), &names); err != nil {
		t.Fatal(err)
	}
	return names
}

func testProfileArraySelect(t *testing.T, remarks []string, saved string, name string) int32 {
	t.Helper()
	input, _ := json.Marshal(remarks)
	index, err := ProfileArraySelect(string(input), saved, name)
	if err != nil {
		t.Fatal(err)
	}
	return index
}

func TestUIProfileArrayNamesByRemarksWithSourceAndIndexFallback(t *testing.T) {
	cases := []struct {
		source, remarks string
		index           int32
		want            string
	}{
		{"configs", "  Node A ", 0, "Node A"},
		{"configs", "", 2, "configs #3"},
		{" configs ", "   ", 1, "configs #2"},
		{"", "", 0, "Profile #1"},
		// Kotlin's trim: no-break and ideographic spaces, but not NEL.
		{"\u00a0src\u3000", "\u2002", 0, "src #1"},
		{"src", "\u0085", 0, "\u0085"},
	}
	for _, c := range cases {
		if got := profileArrayName(c.source, c.remarks, c.index); got != c.want {
			t.Errorf("profileArrayName(%q, %q, %d) = %q, want %q", c.source, c.remarks, c.index, got, c.want)
		}
	}
}

func TestUIProfileArrayEntriesKeepOrder(t *testing.T) {
	if got := testProfileArrayNames(t, "list", "a", ""); !reflect.DeepEqual(got, []string{"a", "list #2"}) {
		t.Fatalf("names = %q", got)
	}
	if got := testProfileArrayNames(t, "list"); len(got) != 0 {
		t.Fatalf("no elements = %q", got)
	}
}

func TestUIProfileArrayMergedSingleElementTakesTheSourceName(t *testing.T) {
	if got := testProfileArrayNames(t, " MASQUE-To-Xray-H3 ", ""); !reflect.DeepEqual(got, []string{"MASQUE-To-Xray-H3"}) {
		t.Fatalf("merged = %q", got)
	}
	if got := testProfileArrayNames(t, "", " "); !reflect.DeepEqual(got, []string{"Profile"}) {
		t.Fatalf("merged without source = %q", got)
	}
	// A lone element that has remarks keeps them.
	if got := testProfileArrayNames(t, "src", "Node"); !reflect.DeepEqual(got, []string{"Node"}) {
		t.Fatalf("lone element = %q", got)
	}
}

func TestUIProfileArraySelectFollowsSavedRemarksThenNameThenFallbackIndex(t *testing.T) {
	remarks := []string{"alpha", "", "beta", ""}
	cases := []struct {
		remarks     []string
		saved, name string
		want        int32
	}{
		// The saved content's remarks win, even after the user renamed the profile.
		{remarks, "beta", "renamed", 2},
		{remarks, "", " alpha ", 0},
		{remarks, "", "sub #2", 1},
		{remarks, "gone", "sub #4", 3},
		// The fallback index must point at an element without remarks.
		{remarks, "", "sub #3", -1},
		{remarks, "", "sub #9", -1},
		{remarks, "missing", "other", -1},
		{[]string{"only"}, "changed", "renamed", 0},
		// Android's regex: Unicode digits and spaces; a number beyond Int is no match.
		{remarks, "", "sub\u3000#\u0662", 1},
		{remarks, "", "sub #2\u0085", 1},
		{remarks, "", "sub#2", -1},
		{remarks, "", "sub #4294967298", -1},
		{remarks, "", "sub #0", -1},
		{[]string{"", ""}, "", "a #1 #2", 1},
	}
	for _, c := range cases {
		if got := testProfileArraySelect(t, c.remarks, c.saved, c.name); got != c.want {
			t.Errorf("select(%q, %q, %q) = %d, want %d", c.remarks, c.saved, c.name, got, c.want)
		}
	}
	if _, err := ProfileArraySelect("not json", "", ""); err == nil {
		t.Fatal("bad remarks list accepted")
	}
}
