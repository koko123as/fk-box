package fk

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestSingBoxServiceContent(t *testing.T) {
	t.Cleanup(SingBoxServiceStopped)
	SingBoxServiceStopped()
	first, err := SingBoxServiceContent(`{"inbounds":[{"type":"tun","tag":"tun-in"}],"route":{"final":"direct"}}`)
	require.NoError(t, err)
	again, err := SingBoxServiceContent(`{"route":{"final":"direct"}}`)
	require.NoError(t, err)
	var root struct {
		Inbounds []map[string]any `json:"inbounds"`
	}
	require.NoError(t, json.Unmarshal([]byte(first), &root))
	require.Len(t, root.Inbounds, 2)
	local := root.Inbounds[1]
	require.Equal(t, "mixed", local["type"])
	require.Equal(t, "127.0.0.1", local["listen"])
	port := local["listen_port"]
	require.NoError(t, json.Unmarshal([]byte(again), &root))
	require.Equal(t, port, root.Inbounds[0]["listen_port"], "a start's fallback content reuses the port")

	require.Empty(t, singBoxLocalProxy())
	require.Equal(t, "", ServiceLocalProxy())
	SingBoxServiceStarted()
	require.True(t, strings.HasPrefix(ServiceLocalProxy(), "http://"+localReturnUser+":"))
	SingBoxServiceStopped()
	require.Equal(t, "", ServiceLocalProxy())

	_, err = SingBoxServiceContent(`{"inbounds":[{"type":"mixed","tag":"` + singBoxLocalTag + `"}]}`)
	require.Error(t, err)
}
