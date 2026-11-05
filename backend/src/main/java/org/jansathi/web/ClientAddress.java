package org.jansathi.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The address a request really came from. {@code X-Forwarded-For} is anyone's to write, so it counts
 * only when the connection comes from a configured proxy, and then we read it from the right,
 * skipping our own proxies: the first address that isn't ours is the client. Anything further left
 * was written by the client. Addresses are used for limits, in memory; they are never stored.
 */
@Component
public class ClientAddress {

	private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

	private final List<Range> trusted;

	public ClientAddress(WebProperties properties) {
		this.trusted = properties.trustedProxies().stream().map(Range::parse).toList();
	}

	public String of(HttpServletRequest request) {
		String peer = request.getRemoteAddr();
		if (!isTrusted(peer)) {
			return peer;
		}
		String header = request.getHeader("X-Forwarded-For");
		if (header == null || header.isBlank()) {
			return peer;
		}
		String[] hops = header.split(",");
		String client = peer;
		for (int i = hops.length - 1; i >= 0; i--) {
			String hop = hops[i].strip();
			if (parse(hop) == null) {
				break;
			}
			client = hop;
			if (!isTrusted(hop)) {
				break;
			}
		}
		return client;
	}

	private boolean isTrusted(String address) {
		InetAddress a = parse(address);
		return a != null && trusted.stream().anyMatch(r -> r.contains(a));
	}

	/** Literal addresses only: InetAddress would otherwise try DNS on whatever a client sent. */
	private static InetAddress parse(String text) {
		if (text == null || !(IPV4.matcher(text).matches() || (text.indexOf(':') >= 0 && text.matches("[0-9a-fA-F:.]+")))) {
			return null;
		}
		try {
			return InetAddress.getByName(text);
		} catch (UnknownHostException e) {
			return null;
		}
	}

	private record Range(byte[] network, int bits) {

		static Range parse(String cidr) {
			int slash = cidr.indexOf('/');
			InetAddress a = ClientAddress.parse(slash < 0 ? cidr : cidr.substring(0, slash));
			if (a == null) {
				throw new IllegalStateException("JANSATHI_TRUSTED_PROXIES holds something that is not an address or range: " + cidr);
			}
			byte[] bytes = a.getAddress();
			return new Range(bytes, slash < 0 ? bytes.length * 8 : Integer.parseInt(cidr.substring(slash + 1)));
		}

		boolean contains(InetAddress address) {
			byte[] b = address.getAddress();
			if (b.length != network.length) {
				return false;
			}
			for (int i = 0, left = bits; left > 0; i++, left -= 8) {
				int mask = left >= 8 ? 0xFF : (0xFF << (8 - left)) & 0xFF;
				if ((b[i] & mask) != (network[i] & mask)) {
					return false;
				}
			}
			return true;
		}
	}
}
