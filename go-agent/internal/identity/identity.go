// Package identity owns the agent's private key, CSR, certificate chain and
// trust bundle. The CSR is generated once and persisted before the first
// Enroll so a retry after a lost reply sends identical bytes.
package identity

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"os"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

const (
	relKey    = "identity/key.pem"
	relCSR    = "identity/csr.der"
	relCert   = "identity/cert.pem"
	relBundle = "identity/trust-bundle.pem"
	relAgent  = "identity/agent_id"
)

type Identity struct {
	s       *store.Store
	key     *ecdsa.PrivateKey
	csr     []byte
	leaf    *x509.Certificate
	chain   [][]byte // DER, leaf first
	bundle  *x509.CertPool
	bundleP []byte
	agentID string
}

func Thumbprint(der []byte) string {
	sum := sha256.Sum256(der)
	return hex.EncodeToString(sum[:])
}

func Load(s *store.Store) (*Identity, error) {
	id := &Identity{s: s}
	keyPEM, err := os.ReadFile(s.Path(relKey))
	switch {
	case errors.Is(err, os.ErrNotExist):
		k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		if err != nil {
			return nil, err
		}
		der, err := x509.MarshalECPrivateKey(k)
		if err != nil {
			return nil, err
		}
		if err := s.Save(store.Blob{RelPath: relKey, Data: pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: der})}); err != nil {
			return nil, err
		}
		id.key = k
	case err != nil:
		return nil, err
	default:
		blk, _ := pem.Decode(keyPEM)
		if blk == nil {
			return nil, fmt.Errorf("identity: %s is not PEM", relKey)
		}
		id.key, err = x509.ParseECPrivateKey(blk.Bytes)
		if err != nil {
			return nil, err
		}
	}
	if b, err := os.ReadFile(s.Path(relCSR)); err == nil {
		id.csr = b
	}
	if b, err := os.ReadFile(s.Path(relCert)); err == nil {
		if err := id.loadChainPEM(b); err != nil {
			return nil, err
		}
	}
	if b, err := os.ReadFile(s.Path(relBundle)); err == nil {
		id.bundleP = b
		id.bundle = x509.NewCertPool()
		if !id.bundle.AppendCertsFromPEM(b) {
			return nil, fmt.Errorf("identity: %s holds no certificates", relBundle)
		}
	}
	if b, err := os.ReadFile(s.Path(relAgent)); err == nil {
		id.agentID = string(b)
	}
	return id, nil
}

func (id *Identity) loadChainPEM(b []byte) error {
	id.chain = nil
	for {
		var blk *pem.Block
		blk, b = pem.Decode(b)
		if blk == nil {
			break
		}
		id.chain = append(id.chain, blk.Bytes)
	}
	if len(id.chain) == 0 {
		return errors.New("identity: cert.pem holds no certificates")
	}
	leaf, err := x509.ParseCertificate(id.chain[0])
	if err != nil {
		return err
	}
	id.leaf = leaf
	return nil
}

func (id *Identity) Enrolled() bool { return id.leaf != nil && id.agentID != "" }
func (id *Identity) AgentID() string { return id.agentID }

func (id *Identity) Thumbprint() string {
	if id.leaf == nil {
		return ""
	}
	return Thumbprint(id.leaf.Raw)
}

// CSR returns the persisted CSR, generating it once.
func (id *Identity) CSR() ([]byte, error) {
	if id.csr != nil {
		return id.csr, nil
	}
	tmpl := &x509.CertificateRequest{Subject: pkix.Name{CommonName: "sm-agent"}}
	der, err := x509.CreateCertificateRequest(rand.Reader, tmpl, id.key)
	if err != nil {
		return nil, err
	}
	if err := id.s.Save(store.Blob{RelPath: relCSR, Data: der}); err != nil {
		return nil, err
	}
	id.csr = der
	return der, nil
}

func (id *Identity) StoreEnrollment(resp msg.EnrollResponse) error {
	if len(resp.CertificateChain) == 0 || resp.AgentID == "" {
		return errors.New("identity: enroll response lacks chain or agent id")
	}
	var chainPEM []byte
	for _, der := range resp.CertificateChain {
		chainPEM = append(chainPEM, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})...)
	}
	if err := id.StoreTrustBundle(resp.TrustBundle); err != nil {
		return err
	}
	if err := id.s.Save(store.Blob{RelPath: relCert, Data: chainPEM}); err != nil {
		return err
	}
	if err := id.s.Save(store.Blob{RelPath: relAgent, Data: []byte(resp.AgentID)}); err != nil {
		return err
	}
	id.agentID = resp.AgentID
	return id.loadChainPEM(chainPEM)
}

func (id *Identity) StoreTrustBundle(b msg.TrustBundle) error {
	var pemBytes []byte
	for _, c := range b.CertsPEM {
		pemBytes = append(pemBytes, c...)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(pemBytes) {
		return errors.New("identity: trust bundle holds no certificates")
	}
	if err := id.s.Save(store.Blob{RelPath: relBundle, Data: pemBytes}); err != nil {
		return err
	}
	id.bundle, id.bundleP = pool, pemBytes
	return nil
}

// EnrollTLS verifies the server against the install-time bundle and sends
// no client certificate — Enroll is the one cert-less RPC.
func (id *Identity) EnrollTLS(bootstrapBundlePEM []byte) (*tls.Config, error) {
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(bootstrapBundlePEM) {
		return nil, errors.New("identity: bootstrap bundle holds no certificates")
	}
	return &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS13, NextProtos: []string{"h2"}}, nil
}

func (id *Identity) ConnectTLS() (*tls.Config, error) {
	if !id.Enrolled() || id.bundle == nil {
		return nil, errors.New("identity: not enrolled")
	}
	return &tls.Config{
		Certificates: []tls.Certificate{{Certificate: id.chain, PrivateKey: id.key, Leaf: id.leaf}},
		RootCAs:      id.bundle,
		MinVersion:   tls.VersionTLS13,
		NextProtos:   []string{"h2"},
	}, nil
}
