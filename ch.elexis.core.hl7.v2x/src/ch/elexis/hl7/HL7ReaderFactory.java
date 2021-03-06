package ch.elexis.hl7;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.Parser;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Hl7InputStreamMessageStringIterator;
import ca.uhn.hl7v2.validation.impl.NoValidation;
import ch.elexis.core.data.interfaces.events.MessageEvent;
import ch.elexis.hl7.v26.Messages;
import ch.elexis.hl7.v2x.HL7ReaderV21;
import ch.elexis.hl7.v2x.HL7ReaderV22;
import ch.elexis.hl7.v2x.HL7ReaderV23;
import ch.elexis.hl7.v2x.HL7ReaderV231;
import ch.elexis.hl7.v2x.HL7ReaderV24;
import ch.elexis.hl7.v2x.HL7ReaderV25;
import ch.elexis.hl7.v2x.HL7ReaderV251;
import ch.elexis.hl7.v2x.HL7ReaderV26;
import ch.rgw.tools.ExHandler;

public enum HL7ReaderFactory {
	
	INSTANCE;
	
	protected List<Message> messageList;
	
	public List<HL7Reader> getReader(File file){
		messageList = new ArrayList<Message>();
		return load(file);
	}
	
	public HL7Reader getReader(String message){
		messageList = new ArrayList<Message>();
		return loadMessage(message);
	}
	
	private List<HL7Reader> load(File file){
		if (!file.canRead()) {
			MessageEvent.fireError(Messages.getString("HL7Reader_CannotReadFile"),
				file.getAbsolutePath());
			return Collections.emptyList();
		}
		List<HL7Reader> ret = new ArrayList<HL7Reader>();
		try {
			InputStream is = new BufferedInputStream(new FileInputStream(file));
			// HAPI utility class will iterate over the messages which appear over an InputStream
			Hl7InputStreamMessageStringIterator stringIterator =
				new Hl7InputStreamMessageStringIterator(is);
			
			Parser p = new PipeParser();
			p.setValidationContext(new NoValidation());
			
			while (stringIterator.hasNext()) {
				String next = stringIterator.next();
				next = assureSaveMSH9Access(next);
				Message hl7Message = p.parse(next);
				messageList.add(hl7Message);
				ret.add(getReaderForMessage(hl7Message));
			}
			is.close();
			return ret;
		} catch (Exception ex) {
			ExHandler.handle(ex);
			MessageEvent.fireError(Messages.getString("HL7Reader_ExceptionWhileReading"),
				ex.getMessage());
		}
		return Collections.emptyList();
	}
	
	private String assureSaveMSH9Access(String hl7Message){
		String separator = "\r";
		String[] splitted = hl7Message.split(separator);
		if (splitted.length < 2) {
			separator = "\n";
			splitted = hl7Message.split(separator);
		}
		
		if (splitted.length < 2) {
			throw new IllegalArgumentException("Could not split message");
		}
		
		String[] mshPart = splitted[0].split("\\|", -1);
		
		if (!mshPart[8].equals("ORU^R01")) {
			mshPart[8] = "ORU^R01";
			splitted[0] = joinStrings(mshPart, "|");
		}
		return joinStrings(splitted, separator);
	}
	
	private String joinStrings(String[] array, String separator){
		StringBuilder builder = new StringBuilder();
		for (String s : array) {
			if (builder.length() != 0) {
				builder.append(separator);
			}
			builder.append(s);
		}
		return builder.toString();
	}
	
	private HL7Reader loadMessage(String message){
		try {
			Parser p = new PipeParser();
			p.setValidationContext(new NoValidation());
			Message hl7Msg = p.parse(message);
			
			messageList.add(hl7Msg);
			return getReaderForMessage(hl7Msg);
		} catch (HL7Exception ex) {
			ExHandler.handle(ex);
			MessageEvent.fireError(Messages.getString("HL7Reader_ExceptionWhileReading"),
				ex.getMessage());
		}
		return null;
	}
	
	private HL7Reader getReaderForMessage(Message message){
		String version = message.getVersion();
		
		if (version.equals("2.1")) {
			return new HL7ReaderV21(message);
		} else if (version.equals("2.2")) {
			return new HL7ReaderV22(message);
		}
		if (version.equals("2.3")) {
			return new HL7ReaderV23(message);
		}
		if (version.equals("2.3.1")) {
			return new HL7ReaderV231(message);
		}
		if (version.equals("2.4")) {
			return new HL7ReaderV24(message);
		}
		if (version.equals("2.5")) {
			return new HL7ReaderV25(message);
		}
		if (version.equals("2.5.1")) {
			return new HL7ReaderV251(message);
		}
		if (version.equals("2.6")) {
			return new HL7ReaderV26(message);
		}
		return null;
	}
}
