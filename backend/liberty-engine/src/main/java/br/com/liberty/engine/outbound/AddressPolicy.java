package br.com.liberty.engine.outbound;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Quais endereços um script pode alcançar: só os públicos. Recusa loopback, rede local, link-local
 * (inclui os metadados de nuvem em 169.254.169.254), uso privado, CGNAT, multicast, reservados e as
 * formas IPv6 que embrulham IPv4 (mapeado, compatível, NAT64 e 6to4).
 */
public interface AddressPolicy {

  boolean allows(InetAddress address);

  /** A política de produção. */
  AddressPolicy PUBLIC_ONLY = AddressPolicy::isPublic;

  static boolean isPublic(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    byte[] b = address.getAddress();
    if (address instanceof Inet4Address) {
      return publicV4(b[0] & 0xff, b[1] & 0xff, b[2] & 0xff);
    }
    if (address instanceof Inet6Address) {
      // fc00::/7 (ULA)
      if ((b[0] & 0xfe) == 0xfc) {
        return false;
      }
      // ::/96 (compatível com IPv4), ::ffff:0:0/96 (mapeado), 64:ff9b::/96 (NAT64)
      boolean zeros10 = true;
      for (int i = 0; i < 10; i++) {
        zeros10 &= b[i] == 0;
      }
      boolean mapped = zeros10 && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
      boolean compatible = zeros10 && b[10] == 0 && b[11] == 0;
      boolean nat64 = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff;
      if (mapped || compatible || nat64) {
        return false;
      }
      // 2002::/16 (6to4) embrulha IPv4: recusado por inteiro
      if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) {
        return false;
      }
      // 2001:db8::/32 (documentação) e 100::/64 (descarte)
      if ((b[0] & 0xff) == 0x20
          && (b[1] & 0xff) == 0x01
          && (b[2] & 0xff) == 0x0d
          && (b[3] & 0xff) == 0xb8) {
        return false;
      }
      return !((b[0] & 0xff) == 0x01 && b[1] == 0 && b[2] == 0 && b[3] == 0);
    }
    return false;
  }

  private static boolean publicV4(int a, int b, int c) {
    if (a == 0 || a == 10 || a == 127 || a >= 224) {
      return false; // "esta rede", privada, loopback, multicast e reservado (inclui broadcast)
    }
    if (a == 100 && b >= 64 && b <= 127) {
      return false; // CGNAT 100.64.0.0/10
    }
    if (a == 169 && b == 254) {
      return false; // link-local e metadados
    }
    if (a == 172 && b >= 16 && b <= 31) {
      return false;
    }
    if (a == 192 && b == 168) {
      return false;
    }
    if (a == 192 && b == 0 && (c == 0 || c == 2)) {
      return false; // 192.0.0.0/24 e documentação 192.0.2.0/24
    }
    if (a == 198 && (b == 18 || b == 19)) {
      return false; // benchmarking
    }
    if (a == 198 && b == 51 && c == 100) {
      return false;
    }
    return !(a == 203 && b == 0 && c == 113);
  }
}
