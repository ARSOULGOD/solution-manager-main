package identity

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"path/filepath"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

// testCA signs the CSR the way the fake gateway will.
func testCA(t *testing.T) (*x509.Certificate, *ecdsa.PrivateKey, []byte) {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "test-ca"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour),
		IsCA: true, KeyUsage: x509.KeyUsageCertSign, BasicConstraintsValid: true,
	}
	der, _ := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	cert, _ := x509.ParseCertificate(der)
	return cert, key, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
}

func sign(t *testing.T, ca *x509.Certificate, caKey *ecdsa.PrivateKey, csrDER []byte) []byte {
	t.Helper()
	csr, err := x509.ParseCertificateRequest(csrDER)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(2), Subject: csr.Subject,
		NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, ca, csr.PublicKey, caKey)
	if err != nil {
		t.Fatal(err)
	}
	return der
}

func TestCSRIsPersistedAndStable(t *testing.T) {
	s, _ := store.Open(filepath.Join(t.TempDir(), "d"))
	id, err := Load(s)
	if err != nil {
		t.Fatal(err)
	}
	if id.Enrolled() {
		t.Fatal("fresh identity must not be enrolled")
	}
	a, _ := id.CSR()
	b, _ := id.CSR()
	if string(a) != string(b) {
		t.Fatal("CSR must be stable across calls")
	}
	if !s.Exists("identity/csr.der") || !s.Exists("identity/key.pem") {
		t.Fatal("CSR and key must be persisted before enroll")
	}
	id2, _ := Load(s)
	c, _ := id2.CSR()
	if string(a) != string(c) {
		t.Fatal("CSR must survive a restart")
	}
}

func TestEnrollmentRoundTrip(t *testing.T) {
	ca, caKey, caPEM := testCA(t)
	s, _ := store.Open(filepath.Join(t.TempDir(), "d"))
	id, _ := Load(s)
	csr, _ := id.CSR()
	leaf := sign(t, ca, caKey, csr)
	err := id.StoreEnrollment(msg.EnrollResponse{
		AgentID: "agent-1", CertificateChain: [][]byte{leaf},
		TrustBundle: msg.TrustBundle{CertsPEM: [][]byte{caPEM}}, ConnectEndpoint: "dp:443",
	})
	if err != nil {
		t.Fatal(err)
	}
	if !id.Enrolled() || id.AgentID() != "agent-1" || id.Thumbprint() != Thumbprint(leaf) {
		t.Fatalf("enrolled=%v agent=%s", id.Enrolled(), id.AgentID())
	}
	cfg, err := id.ConnectTLS()
	if err != nil || len(cfg.Certificates) != 1 || cfg.RootCAs == nil {
		t.Fatalf("ConnectTLS: %v", err)
	}
	ecfg, err := id.EnrollTLS(caPEM)
	if err != nil || len(ecfg.Certificates) != 0 {
		t.Fatalf("EnrollTLS must carry no client cert: %v", err)
	}
	id2, _ := Load(s)
	if !id2.Enrolled() || id2.Thumbprint() != id.Thumbprint() {
		t.Fatal("enrollment must survive a restart")
	}
}
