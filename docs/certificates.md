# Selene certificate layout

Use a private CA under operator control. Give the Rust backend a server certificate and CA certificate for authorized frontends. Give Velocity and each Paper authentication server a distinct client certificate. Java nodes store their own private key/certificate chain in PKCS12 and a trust store containing the peer CA. Rust reads PEM files.

Private keys stay on the node that generated them. The files moved between machines are CA certificates and public leaf certificates used for explicit fingerprint authorization. Protect all key files with operating-system permissions and keep PKCS12 passwords in the environment variables named by each component's local config.

Example CA and leaf creation with OpenSSL (replace names/SANs before use):

```sh
openssl genpkey -algorithm Ed25519 -out selene-ca.key
openssl req -x509 -new -key selene-ca.key -out selene-ca.pem -days 3650 -subj '/CN=Selene private CA'

openssl genpkey -algorithm Ed25519 -out proxy.key
openssl req -new -key proxy.key -out proxy.csr -subj '/CN=proxy.example.invalid'
printf 'subjectAltName=DNS:proxy.example.invalid\nextendedKeyUsage=clientAuth,serverAuth\n' > proxy.ext
openssl x509 -req -in proxy.csr -CA selene-ca.pem -CAkey selene-ca.key -CAcreateserial -out proxy.pem -days 365 -extfile proxy.ext
openssl pkcs12 -export -inkey proxy.key -in proxy.pem -certfile selene-ca.pem -out proxy.p12
keytool -importcert -noprompt -alias selene-ca -file selene-ca.pem -keystore trust.p12 -storetype PKCS12
openssl x509 -in proxy.pem -noout -fingerprint -sha256
```

Create separate leaves for backend, Velocity and every Paper authentication server. The backend server leaf needs its reachable DNS name or numeric IP in SAN because Java hostname verification remains enabled. Rotate a node by trusting the new public certificate/CA, replacing its local identity, checking connectivity, and then revoking/removing the old trust.

For `PROXY_AUTH`, the exact file ownership is:

| Process | Private identity kept locally | Public trust material |
| --- | --- | --- |
| Velocity | `plugins/selene/proxy.p12` | `plugins/selene/trust.p12` trusts the backend CA and `auth-server-certificate-sha256` pins Paper's public leaf for the relay |
| Paper auth server | `plugins/Selene/paper-identity.p12` | `plugins/Selene/proxy-trust.p12` trusts the CA that signed Velocity's relay certificate |
| Rust backend | `backend/tls/server-key.pem` + `server.pem` | `backend/tls/frontend-ca.pem` trusts the CA that signed Velocity's client certificate |

The Paper `host` must match a DNS name or IP in Velocity's relay certificate SAN. When both run on one machine and Paper uses `host=localhost`, include `DNS:localhost` in that certificate. The Rust backend certificate SAN must similarly match the host in Velocity's `backend-endpoint`.
