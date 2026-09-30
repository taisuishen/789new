package com.bingo789.game.security;

import java.net.InetAddress;
import java.util.List;

/**
 * IPv4/IPv6 CIDR allow-list. Literal addresses only: {@link InetAddress#ofLiteral} never does a DNS lookup.
 * Entries without a prefix length match a single address.
 */
public final class CidrMatcher {

    private final List<Block> blocks;

    private CidrMatcher(List<Block> blocks) {
        this.blocks = blocks;
    }

    public static CidrMatcher of(List<String> cidrs) {
        return new CidrMatcher(cidrs.stream().map(String::strip).filter(s -> !s.isEmpty()).map(CidrMatcher::parse).toList());
    }

    public boolean matches(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        byte[] address;
        try {
            address = InetAddress.ofLiteral(ip.strip()).getAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (Block block : blocks) {
            if (block.matches(address)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    private static Block parse(String cidr) {
        int slash = cidr.indexOf('/');
        byte[] network = InetAddress.ofLiteral(slash < 0 ? cidr : cidr.substring(0, slash)).getAddress();
        int prefix = slash < 0 ? network.length * 8 : Integer.parseInt(cidr.substring(slash + 1));
        if (prefix < 0 || prefix > network.length * 8) {
            throw new IllegalArgumentException("invalid CIDR " + cidr);
        }
        return new Block(network, prefix);
    }

    private record Block(byte[] network, int prefix) {

        boolean matches(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefix % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
