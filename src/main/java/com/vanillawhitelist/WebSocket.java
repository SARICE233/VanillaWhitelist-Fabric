package com.vanillawhitelist;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 最小 RFC6455 实现：握手辅助 + 帧编解码。
 * 服务端（WsSession）与客户端（WsClient）共用，避免两套实现走偏。
 */
final class WebSocket {

	static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

	private static final SecureRandom RANDOM = new SecureRandom();

	private WebSocket() { }

	/** 生成 Sec-WebSocket-Key（客户端用） */
	static String randomKey() {
		byte[] b = new byte[16];
		RANDOM.nextBytes(b);
		return Base64.getEncoder().encodeToString(b);
	}

	/** 由 key 计算 Sec-WebSocket-Accept（服务端回包用，客户端校验用） */
	static String accept(String key) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-1");
		return Base64.getEncoder().encodeToString(
				md.digest((key + GUID).getBytes(StandardCharsets.US_ASCII)));
	}

	/** 一帧 */
	static final class Frame {
		final int opcode;
		final byte[] payload;
		Frame(int opcode, byte[] payload) {
			this.opcode = opcode;
			this.payload = payload;
		}
	}

	/** @return 读到的帧；流结束返回 null */
	static Frame readFrame(InputStream in) throws IOException {
		int b0 = in.read();
		if (b0 < 0) return null;
		int b1 = in.read();
		if (b1 < 0) return null;
		int opcode = b0 & 0x0F;
		boolean masked = (b1 & 0x80) != 0;
		long len = b1 & 0x7F;
		if (len == 126) {
			len = ((long) readByte(in) << 8) | readByte(in);
		} else if (len == 127) {
			len = 0;
			for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
		}
		if (len > 8 * 1024 * 1024) throw new IOException("frame too large: " + len);
		byte[] mask = new byte[4];
		if (masked) readFully(in, mask, 4);
		byte[] payload = new byte[(int) len];
		readFully(in, payload, (int) len);
		if (masked) {
			for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
		}
		return new Frame(opcode, payload);
	}

	/**
	 * 写一帧。
	 * @param mask 客户端发出的帧必须掩码（RFC6455 规定），服务端发出的必须不掩码
	 */
	static void writeFrame(OutputStream out, int opcode, byte[] data, boolean mask) throws IOException {
		int n = data.length;
		byte[] header;
		if (n < 126) {
			header = new byte[2];
			header[1] = (byte) n;
		} else if (n < 65536) {
			header = new byte[4];
			header[1] = (byte) 126;
			header[2] = (byte) (n >> 8);
			header[3] = (byte) n;
		} else {
			header = new byte[10];
			header[1] = (byte) 127;
			for (int i = 0; i < 8; i++) header[2 + i] = (byte) (((long) n) >> (8 * (7 - i)));
		}
		header[0] = (byte) (0x80 | opcode);
		if (mask) {
			header[1] |= (byte) 0x80;
			byte[] mk = new byte[4];
			RANDOM.nextBytes(mk);
			// header + mask + payload
			synchronized (out) {
				out.write(header);
				out.write(mk);
				byte[] masked = new byte[n];
				for (int i = 0; i < n; i++) masked[i] = (byte) (data[i] ^ mk[i % 4]);
				out.write(masked);
				out.flush();
			}
		} else {
			synchronized (out) {
				out.write(header);
				out.write(data);
				out.flush();
			}
		}
	}

	private static int readByte(InputStream in) throws IOException {
		int b = in.read();
		if (b < 0) throw new IOException("EOF");
		return b;
	}

	private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
		int off = 0;
		while (off < len) {
			int r = in.read(buf, off, len - off);
			if (r < 0) throw new IOException("EOF");
			off += r;
		}
	}
}
