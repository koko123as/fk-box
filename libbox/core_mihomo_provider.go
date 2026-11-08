package libbox

// mihomo's external providers for FlClash's Resources/Providers views, ported
// from FlClash v0.9.3 core/provider.go (be84736, GPL-3.0): every proxy and
// rule provider that is not inline ("Compatible"), sorted by name, in the
// ExternalProvider JSON shape FlClash's lib/models/core.dart reads. They exist
// only while mihomo runs in this process (startMihomoCore); otherwise the list
// is empty.

import (
	"cmp"
	"encoding/json"
	"fmt"
	"slices"
	"time"

	mihomoProvider "github.com/metacubex/mihomo/constant/provider"
	mihomoTunnel "github.com/metacubex/mihomo/tunnel"
)

type mihomoExternalProvider struct {
	Name             string          `json:"name"`
	Type             string          `json:"type"`
	Path             string          `json:"path,omitempty"`
	Count            int             `json:"count"`
	SubscriptionInfo json.RawMessage `json:"subscription-info,omitempty"`
	VehicleType      string          `json:"vehicle-type"`
	UpdateAt         time.Time       `json:"update-at"`
}

type mihomoCountedProvider interface {
	mihomoProvider.Provider
	Count() int
}

func mihomoExternalProviders() map[string]mihomoCountedProvider {
	providers := map[string]mihomoCountedProvider{}
	for name, provider := range mihomoTunnel.Providers() {
		if provider.VehicleType() != mihomoProvider.Compatible {
			providers[name] = provider
		}
	}
	for name, provider := range mihomoTunnel.RuleProviders() {
		if provider.VehicleType() != mihomoProvider.Compatible {
			providers[name] = provider
		}
	}
	return providers
}

func toMihomoExternalProvider(provider mihomoCountedProvider) mihomoExternalProvider {
	external := mihomoExternalProvider{
		Name:        provider.Name(),
		Type:        provider.Type().String(),
		Count:       provider.Count(),
		VehicleType: provider.VehicleType().String(),
	}
	// mihomo's own MarshalJSON carries the update time and, for proxy
	// providers, the subscription-userinfo the provider's server sent.
	if encoded, err := json.Marshal(provider); err == nil {
		var fields struct {
			UpdatedAt        time.Time       `json:"updatedAt"`
			SubscriptionInfo json.RawMessage `json:"subscriptionInfo"`
		}
		if json.Unmarshal(encoded, &fields) == nil {
			external.UpdateAt = fields.UpdatedAt
			if len(fields.SubscriptionInfo) > 0 && string(fields.SubscriptionInfo) != "null" {
				external.SubscriptionInfo = fields.SubscriptionInfo
			}
		}
	}
	return external
}

// MihomoExternalProviders returns FlClash's provider.list answer as JSON.
func MihomoExternalProviders() (string, error) {
	providers := mihomoExternalProviders()
	list := make([]mihomoExternalProvider, 0, len(providers))
	for _, provider := range providers {
		list = append(list, toMihomoExternalProvider(provider))
	}
	slices.SortFunc(list, func(a, b mihomoExternalProvider) int {
		return cmp.Compare(a.Name, b.Name)
	})
	encoded, err := json.Marshal(list)
	return string(encoded), err
}

// MihomoExternalProvider returns one provider as JSON, or "" when mihomo has
// no external provider of that name.
func MihomoExternalProvider(name string) (string, error) {
	provider, ok := mihomoExternalProviders()[name]
	if !ok {
		return "", nil
	}
	encoded, err := json.Marshal(toMihomoExternalProvider(provider))
	return string(encoded), err
}

// UpdateMihomoProvider refreshes one external provider from its source, as
// FlClash's provider.update does; it returns once the update finished.
func UpdateMihomoProvider(name string) error {
	provider, ok := mihomoExternalProviders()[name]
	if !ok {
		return fmt.Errorf("provider %s is not loaded", name)
	}
	return provider.Update()
}
