package org.safehaus.adbcj.postgresql.backend;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;

import org.apache.mina.common.IoBuffer;
import org.apache.mina.common.IoSession;
import org.apache.mina.filter.codec.CumulativeProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.safehaus.adbcj.postgresql.ConfigurationVariable;
import org.safehaus.adbcj.postgresql.IoSessionUtil;
import org.safehaus.adbcj.postgresql.PgConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PgBackendMessageDecoder extends CumulativeProtocolDecoder {

	private final Logger logger = LoggerFactory.getLogger(PgBackendMessageDecoder.class);
	
	@Override
	protected boolean doDecode(IoSession session, IoBuffer in, ProtocolDecoderOutput out) throws Exception {
		logger.trace("Decoding message");
		// TODO Make MySQL decoder more like postgresql decoder
		
		// Check to see if we have enough data to read the message type and message length
		if (in.remaining() < 5) {
			return false;
		}

		// Check to see if we've read the entire message
		int length;
		try {
			in.mark();
			in.get(); // Throw away type
			length = in.getInt() - 4;  // Subtract 4 because we don't want to include the length field itself
			if (in.remaining() < length) {
				logger.trace("Need more data");
				return false;
			}
		} finally {
			in.reset();
		}
		
		// Get the type and length
		byte typeValue = in.get();
		BackendMessageType type = BackendMessageType.fromValue(typeValue);
		in.getInt(); // Throw away length because we've already fetched it
		
		// Create a buffer for just the current message being processed
		IoBuffer buffer = in.duplicate();
		buffer.limit(buffer.position() + length);
		in.skip(length); // Skip the bytes the will be processed with 'buffer'
		
		// If type is null, throw exception
		if (type == null) {
			throw new IllegalStateException("Don't recognize message of type " + typeValue);
		}
		
		logger.debug("Decoding message ot type {}", type);
		
		// TODO Implement decoder for remaining backend message types
		switch (type) {
		// Message types that don't have any extra data
		case BIND_COMPLETE:
		case CLOSE_COMPLETE:
		case COPY_DONE:
		case EMPTY_QUERY_RESPONSE:
		case NO_DATA:
		case PARSE_COMPLETE:
		case PORTAL_SUSPENDED:
			out.write(new BackendMessage(type));
			break;
		case AUTHENTICATION:
			decodeAuthentication(session, buffer, out);
			break;
		case KEY:
			decodeKey(session, buffer, out);
			break;
		case PARAMETER_STATUS:
			decodeParameterStatus(session, buffer, out);
			break;
		case READY_FOR_QUERY:
			decodeReadyForQuery(session, buffer, out);
			break;
		default:
			throw new IllegalStateException(String.format("Messages of type %s are not implemented", type)); 
		}

		if (buffer.hasRemaining()) {
			throw new IllegalStateException(String.format("buffer has %d unread bytes after decoding message of type %s", buffer.remaining(), type));
		}
		
		return in.hasRemaining();
	}

	private void decodeAuthentication(IoSession session, IoBuffer buffer, ProtocolDecoderOutput out) {
		// Get authentication type
		AuthenticationType authenticationType = buffer.getEnumInt(AuthenticationType.class);
		
		AuthenticationMessage message;
		switch(authenticationType) {
		// Authentication types that don't have a payload
		case OK:
		case KERBEROS_5:
		case CLEARTEXT_PASSWORD:
		case SCM_CREDENTIAL:
		case GSS:
			message = new AuthenticationMessage(authenticationType);
			break;
		// Get crypt salt
		case CRYPT_PASSWORD:
			byte[] cryptSalt = new byte[2];
			buffer.get(cryptSalt);
			message = new AuthenticationMessage(authenticationType, cryptSalt);
			break;
		// Get md5 salt
		case MD5_PASSWORD:
			byte[] md5Salt = new byte[4];
			buffer.get(md5Salt);
			message = new AuthenticationMessage(authenticationType, md5Salt);
			break;
		// Get GSSAPI authentication data
		case GSS_CONTINUE:
			byte[] data = new byte[buffer.remaining()];
			buffer.get(data);
			message = new AuthenticationMessage(authenticationType, data);
			break;
		case UNKNOWN:
		default:
			throw new IllegalStateException("Don't know how to handle authentication type of " + authenticationType);
		}
		
		out.write(message);
	}

	private void decodeKey(IoSession session, IoBuffer buffer, ProtocolDecoderOutput out) {
		int pid = buffer.getInt();
		int key = buffer.getInt();
		KeyMessage message = new KeyMessage(pid, key);
		out.write(message);
	}

	private void decodeParameterStatus(IoSession session, IoBuffer buffer, ProtocolDecoderOutput out) throws CharacterCodingException {
		PgConnection connection = IoSessionUtil.getConnection(session);
		CharsetDecoder decoder = connection.getBackendCharset().newDecoder();
		String name = buffer.getString(decoder);
		String value = buffer.getString(decoder);
		ConfigurationVariable cv = ConfigurationVariable.fromName(name);
		if (cv == null) {
			logger.warn("No ConfigurationVariable entry for {}", name);
		}
		ParameterMessage message = new ParameterMessage(cv, value);
		out.write(message);
	}

	private void decodeReadyForQuery(IoSession session, IoBuffer buffer, ProtocolDecoderOutput out) {
		char s = (char)buffer.get();
		Status status;
		switch(s) {
		case 'E':
			status = Status.ERROR;
			break;
		case 'I':
			status = Status.IDLE;
			break;
		case 'T':
			status = Status.TRANSACTION;
			break;
		default:
			throw new IllegalStateException("Unrecognized server status " + s);	
		}
		ReadyMessage message = new ReadyMessage(status);
		out.write(message);
	}

}