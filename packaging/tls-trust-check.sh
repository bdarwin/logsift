#!/usr/bin/env bash
# Does the app's runtime trust a certificate only the computer trusts (an office proxy's)?
#   tls-trust-check.sh make DIR                     a test authority (DIR/ca.pem) and a server signed by it
#   tls-trust-check.sh check JAVA CP DIR trusted|untrusted
# The build makes the authority, checks it isn't trusted, adds it to the computer's store, checks again.
set -euo pipefail
case "$1" in
  make)
    d=$2; mkdir -p "$d"; rm -f "$d"/*.p12 "$d"/*.pem "$d"/*.csr
    kt() { keytool -storepass changeit -storetype PKCS12 "$@"; }
    kt -genkeypair -alias ca -keyalg RSA -keysize 2048 -validity 2 -keystore "$d/ca.p12" \
       -dname "CN=Logsift Test Office CA" -ext bc:c
    kt -exportcert -alias ca -keystore "$d/ca.p12" -rfc -file "$d/ca.pem"
    kt -genkeypair -alias server -keyalg RSA -keysize 2048 -validity 2 -keystore "$d/server.p12" -dname "CN=localhost"
    kt -certreq -alias server -keystore "$d/server.p12" -file "$d/server.csr"
    kt -gencert -alias ca -keystore "$d/ca.p12" -infile "$d/server.csr" -outfile "$d/server.pem" -rfc -validity 2 \
       -ext san=dns:localhost,ip:127.0.0.1 -ext eku=serverAuth
    kt -importcert -alias ca -keystore "$d/server.p12" -file "$d/ca.pem" -noprompt
    kt -importcert -alias server -keystore "$d/server.p12" -file "$d/server.pem" -noprompt
    ;;
  check)
    "$2" -cp "$3" logsift.core.TlsTrustCheck "$4/server.p12" changeit "$5"
    ;;
  *) echo "make or check"; exit 2 ;;
esac
