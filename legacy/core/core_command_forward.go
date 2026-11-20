package libbox

// The sing-box-only StartedService methods: Tailscale, Taildrop, OpenConnect
// and OpenVPN actions on an endpoint of the running sing-box. They are
// forwarded to its attached service; while Xray or mihomo runs (or nothing
// does) the idle service answers them, as libbox's idle service did.

import (
	"context"

	"github.com/sagernet/sing-box/daemon"
	"google.golang.org/grpc"
	"google.golang.org/protobuf/types/known/emptypb"
)

func (s *coreStartedService) StartTailscalePing(request *daemon.TailscalePingRequest, server grpc.ServerStreamingServer[daemon.TailscalePingResponse]) error {
	return s.singBox().StartTailscalePing(request, server)
}

func (s *coreStartedService) SetTailscaleExitNode(ctx context.Context, request *daemon.SetTailscaleExitNodeRequest) (*emptypb.Empty, error) {
	return s.singBox().SetTailscaleExitNode(ctx, request)
}

func (s *coreStartedService) TailscaleLogout(ctx context.Context, request *daemon.TailscaleLogoutRequest) (*emptypb.Empty, error) {
	return s.singBox().TailscaleLogout(ctx, request)
}

func (s *coreStartedService) GetTailscaleCertificate(ctx context.Context, request *daemon.TailscaleCertificateRequest) (*daemon.TailscaleCertificate, error) {
	return s.singBox().GetTailscaleCertificate(ctx, request)
}

func (s *coreStartedService) StartTailscaleSSHSession(server grpc.BidiStreamingServer[daemon.TailscaleSSHClientMessage, daemon.TailscaleSSHServerMessage]) error {
	return s.singBox().StartTailscaleSSHSession(server)
}

func (s *coreStartedService) MarkTaildropInboxRead(ctx context.Context, request *daemon.MarkTaildropInboxReadRequest) (*emptypb.Empty, error) {
	return s.singBox().MarkTaildropInboxRead(ctx, request)
}

func (s *coreStartedService) SendTaildropFiles(server grpc.BidiStreamingServer[daemon.TaildropSendClientMessage, daemon.TaildropSendServerMessage]) error {
	return s.singBox().SendTaildropFiles(server)
}

func (s *coreStartedService) DownloadTaildropFile(request *daemon.DownloadTaildropFileRequest, server grpc.ServerStreamingServer[daemon.DownloadTaildropFileChunk]) error {
	return s.singBox().DownloadTaildropFile(request, server)
}

func (s *coreStartedService) DeleteTaildropFile(ctx context.Context, request *daemon.DeleteTaildropFileRequest) (*emptypb.Empty, error) {
	return s.singBox().DeleteTaildropFile(ctx, request)
}

func (s *coreStartedService) CancelTaildropReceiving(ctx context.Context, request *daemon.CancelTaildropReceivingRequest) (*emptypb.Empty, error) {
	return s.singBox().CancelTaildropReceiving(ctx, request)
}

func (s *coreStartedService) SubmitOpenConnectAuthResponse(ctx context.Context, request *daemon.OpenConnectAuthResponseSubmission) (*emptypb.Empty, error) {
	return s.singBox().SubmitOpenConnectAuthResponse(ctx, request)
}

func (s *coreStartedService) CancelOpenConnectAuthChallenge(ctx context.Context, request *daemon.OpenConnectAuthChallengeCancel) (*emptypb.Empty, error) {
	return s.singBox().CancelOpenConnectAuthChallenge(ctx, request)
}

func (s *coreStartedService) SubmitOpenVPNChallengeResponse(ctx context.Context, request *daemon.OpenVPNChallengeSubmission) (*emptypb.Empty, error) {
	return s.singBox().SubmitOpenVPNChallengeResponse(ctx, request)
}

func (s *coreStartedService) CancelOpenVPNChallenge(ctx context.Context, request *daemon.OpenVPNChallengeCancel) (*emptypb.Empty, error) {
	return s.singBox().CancelOpenVPNChallenge(ctx, request)
}
