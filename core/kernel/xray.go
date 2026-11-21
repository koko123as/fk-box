package kernel

// Xray's gRPC API on a Unix socket (the api inbound's listen path; Xray
// app/commander). The messages are encoded by hand with protowire so that
// fk-core needs none of xray-core's generated packages; the field numbers
// are those of Xray-core's app/stats/command, app/router/command and
// app/observatory protos.

import (
	"context"
	"fmt"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/encoding"
	"google.golang.org/protobuf/encoding/protowire"
)

// rawCodec sends and receives already encoded protobuf messages.
type rawCodec struct{}

func (rawCodec) Name() string { return "proto" }

func (rawCodec) Marshal(v any) ([]byte, error) {
	if message, ok := v.(*rawMessage); ok {
		return message.data, nil
	}
	return nil, fmt.Errorf("rawCodec: unexpected %T", v)
}

func (rawCodec) Unmarshal(data []byte, v any) error {
	if message, ok := v.(*rawMessage); ok {
		message.data = append(message.data[:0], data...)
		return nil
	}
	return fmt.Errorf("rawCodec: unexpected %T", v)
}

var _ encoding.Codec = rawCodec{}

type rawMessage struct{ data []byte }

// Xray is a client of one Xray API server.
type Xray struct {
	conn *grpc.ClientConn
}

// DialXray connects to the API server listening at socket.
func DialXray(socket string) (*Xray, error) {
	conn, err := grpc.NewClient("unix://"+socket,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithDefaultCallOptions(grpc.ForceCodec(rawCodec{})))
	if err != nil {
		return nil, err
	}
	return &Xray{conn: conn}, nil
}

// Close closes the connection.
func (x *Xray) Close() error { return x.conn.Close() }

func (x *Xray) invoke(ctx context.Context, method string, request []byte) ([]byte, error) {
	in := &rawMessage{data: request}
	out := &rawMessage{}
	if err := x.conn.Invoke(ctx, method, in, out); err != nil {
		return nil, err
	}
	return out.data, nil
}

// fields walks the top-level fields of message.
func fields(message []byte, visit func(number protowire.Number, kind protowire.Type, value []byte, varint uint64) error) error {
	for len(message) > 0 {
		number, kind, n := protowire.ConsumeTag(message)
		if n < 0 {
			return protowire.ParseError(n)
		}
		message = message[n:]
		switch kind {
		case protowire.VarintType:
			value, n := protowire.ConsumeVarint(message)
			if n < 0 {
				return protowire.ParseError(n)
			}
			if err := visit(number, kind, nil, value); err != nil {
				return err
			}
			message = message[n:]
		case protowire.BytesType:
			value, n := protowire.ConsumeBytes(message)
			if n < 0 {
				return protowire.ParseError(n)
			}
			if err := visit(number, kind, value, 0); err != nil {
				return err
			}
			message = message[n:]
		default:
			n := protowire.ConsumeFieldValue(number, kind, message)
			if n < 0 {
				return protowire.ParseError(n)
			}
			message = message[n:]
		}
	}
	return nil
}

func appendString(b []byte, number protowire.Number, value string) []byte {
	if value == "" {
		return b
	}
	b = protowire.AppendTag(b, number, protowire.BytesType)
	return protowire.AppendString(b, value)
}

func appendBool(b []byte, number protowire.Number, value bool) []byte {
	if !value {
		return b
	}
	b = protowire.AppendTag(b, number, protowire.VarintType)
	return protowire.AppendVarint(b, 1)
}

// QueryStats is StatsService.QueryStats: every counter whose name contains
// pattern.
func (x *Xray) QueryStats(ctx context.Context, pattern string, reset bool) (map[string]int64, error) {
	var request []byte
	request = appendString(request, 1, pattern)
	request = appendBool(request, 2, reset)
	response, err := x.invoke(ctx, "/xray.app.stats.command.StatsService/QueryStats", request)
	if err != nil {
		return nil, err
	}
	stats := map[string]int64{}
	err = fields(response, func(number protowire.Number, kind protowire.Type, value []byte, _ uint64) error {
		if number != 1 || kind != protowire.BytesType {
			return nil
		}
		var name string
		var count int64
		err := fields(value, func(number protowire.Number, kind protowire.Type, value []byte, varint uint64) error {
			switch {
			case number == 1 && kind == protowire.BytesType:
				name = string(value)
			case number == 2 && kind == protowire.VarintType:
				count = int64(varint)
			}
			return nil
		})
		stats[name] = count
		return err
	})
	return stats, err
}

// Goroutines is StatsService.GetSysStats's NumGoroutine; it also tells that
// the API answers.
func (x *Xray) Goroutines(ctx context.Context) (int32, error) {
	response, err := x.invoke(ctx, "/xray.app.stats.command.StatsService/GetSysStats", nil)
	if err != nil {
		return 0, err
	}
	var goroutines int32
	err = fields(response, func(number protowire.Number, kind protowire.Type, _ []byte, varint uint64) error {
		if number == 1 && kind == protowire.VarintType {
			goroutines = int32(varint)
		}
		return nil
	})
	return goroutines, err
}

// OverrideBalancer is RoutingService.OverrideBalancerTarget ("" clears it).
func (x *Xray) OverrideBalancer(ctx context.Context, balancer, target string) error {
	var request []byte
	request = appendString(request, 1, balancer)
	request = appendString(request, 2, target)
	_, err := x.invoke(ctx, "/xray.app.router.command.RoutingService/OverrideBalancerTarget", request)
	return err
}

// BalancerInfo is RoutingService.GetBalancerInfo: the override target and
// the targets the strategy picks.
func (x *Xray) BalancerInfo(ctx context.Context, balancer string) (override string, principle []string, err error) {
	response, err := x.invoke(ctx, "/xray.app.router.command.RoutingService/GetBalancerInfo", appendString(nil, 1, balancer))
	if err != nil {
		return "", nil, err
	}
	err = fields(response, func(number protowire.Number, kind protowire.Type, value []byte, _ uint64) error {
		if number != 1 || kind != protowire.BytesType {
			return nil
		}
		return fields(value, func(number protowire.Number, kind protowire.Type, value []byte, _ uint64) error {
			if kind != protowire.BytesType {
				return nil
			}
			switch number {
			case 5:
				return fields(value, func(number protowire.Number, kind protowire.Type, value []byte, _ uint64) error {
					if number == 2 && kind == protowire.BytesType {
						override = string(value)
					}
					return nil
				})
			case 6:
				return fields(value, func(number protowire.Number, kind protowire.Type, value []byte, _ uint64) error {
					if number == 1 && kind == protowire.BytesType {
						principle = append(principle, string(value))
					}
					return nil
				})
			}
			return nil
		})
	})
	return override, principle, err
}
