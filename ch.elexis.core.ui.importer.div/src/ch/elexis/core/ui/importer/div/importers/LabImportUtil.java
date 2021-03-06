package ch.elexis.core.ui.importer.div.importers;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.elexis.core.data.activator.CoreHub;
import ch.elexis.core.data.events.ElexisEvent;
import ch.elexis.core.data.events.ElexisEventDispatcher;
import ch.elexis.data.Kontakt;
import ch.elexis.data.LabItem;
import ch.elexis.data.LabMapping;
import ch.elexis.data.LabOrder;
import ch.elexis.data.LabOrder.State;
import ch.elexis.data.LabResult;
import ch.elexis.data.Labor;
import ch.elexis.data.Patient;
import ch.elexis.data.Query;
import ch.rgw.tools.TimeTool;

/**
 * Utility class that provides basic functionality a Lab importer implementation needs. Lab
 * importers should use this class!
 * 
 * @author thomashu
 * 
 */
public class LabImportUtil {
	private static Logger logger = LoggerFactory.getLogger(LabImportUtil.class);
	
	/**
	 * Searches for a Labor matching the identifier as part of the Kuerzel or Name attribute. If no
	 * matching Labor is found, a new Labor is created with identifier as Kuerzel.
	 * 
	 * @param identifier
	 * @return
	 */
	public static Labor getOrCreateLabor(String identifier){
		if (identifier == null || identifier.isEmpty()) {
			throw new IllegalArgumentException("Labor identifier [" + identifier + "] invalid.");
		}
		Labor labor = null;
		Query<Labor> qbe = new Query<Labor>(Labor.class);
		qbe.add(Kontakt.FLD_SHORT_LABEL, Query.LIKE, "%" + identifier + "%"); //$NON-NLS-1$ //$NON-NLS-2$
		qbe.or();
		qbe.add(Kontakt.FLD_NAME1, Query.LIKE, "%" + identifier + "%"); //$NON-NLS-1$ //$NON-NLS-2$
		List<Labor> results = qbe.execute();
		if (results.isEmpty()) {
			labor = new Labor(identifier, "Labor " + identifier); //$NON-NLS-1$
			logger.warn("Found no Labor for identifier [" + identifier
				+ "]. Created new Labor contact.");
		} else {
			labor = results.get(0);
			if (results.size() > 1) {
				logger.warn("Found more than one Labor for identifier [" + identifier
					+ "]. This can cause problems when importing results.");
			}
		}
		return labor;
	}
	
	/**
	 * Searches for a LabItem with an existing LabMapping for the identifier and the labor. If there
	 * is no LabMapping, for backwards compatibility the LaborId and Kürzel attributes of all
	 * LabItems will be used to find a match.
	 * 
	 * @param identifier
	 * @param labor
	 * @return
	 */
	public static LabItem getLabItem(String identifier, Labor labor){
		LabMapping mapping = LabMapping.getByContactAndItemName(labor.getId(), identifier);
		if (mapping != null) {
			return mapping.getLabItem();
		}
		
		LabItem labItem = null;
		Query<LabItem> qbe = new Query<LabItem>(LabItem.class);
		qbe.add(LabItem.LAB_ID, Query.EQUALS, labor.getId());
		qbe.add(LabItem.SHORTNAME, Query.EQUALS, identifier);
		List<LabItem> list = qbe.execute();
		if (!list.isEmpty()) {
			labItem = list.get(0);
			if (list.size() > 1) {
				logger.warn("Found more than one LabItem for identifier [" + identifier
					+ "] and Labor [" + labor.getLabel(true)
					+ "]. This can cause problems when importing results.");
			}
		}
		return labItem;
	}
	
	/**
	 * Search for a LabResult with matching patient, item and timestamps. The timestamp attributes
	 * can be null if not relevant for the search, but at least one timestamp has to be specified.
	 * 
	 * @param patient
	 * @param timestamp
	 * @param item
	 * @return
	 */
	public static List<LabResult> getLabResults(Patient patient, LabItem item, TimeTool date,
		TimeTool analyseTime, TimeTool observationTime){
		
		if (date == null && analyseTime == null && observationTime == null) {
			throw new IllegalArgumentException("No timestamp specified.");
		}
		
		Query<LabResult> qr = new Query<LabResult>(LabResult.class);
		qr.add(LabResult.PATIENT_ID, Query.EQUALS, patient.getId());
		qr.add(LabResult.ITEM_ID, Query.EQUALS, item.getId());
		if (date != null) {
			qr.add(LabResult.DATE, Query.EQUALS, date.toString(TimeTool.DATE_GER));
		}
		if (analyseTime != null) {
			qr.add(LabResult.ANALYSETIME, Query.EQUALS, analyseTime.toString(TimeTool.TIMESTAMP));
		}
		if (observationTime != null) {
			qr.add(LabResult.OBSERVATIONTIME, Query.EQUALS,
				observationTime.toString(TimeTool.TIMESTAMP));
		}
		return qr.execute();
	}
	
	/**
	 * Import a list of TransientLabResults. Create LabOrder objects for new results.
	 */
	public static void importLabResults(List<TransientLabResult> results, ImportUiHandler uiHandler){
		boolean overWriteAll = false;
		String orderId = null;
		for (TransientLabResult transientLabResult : results) {
			List<LabResult> existing =
				LabImportUtil.getLabResults(transientLabResult.patient, transientLabResult.labItem,
					transientLabResult.date, transientLabResult.analyseTime,
					transientLabResult.observationTime);
			if (existing.isEmpty()) {
				if (orderId == null) {
					orderId = LabOrder.getNextOrderId();
				}
				createLabResult(transientLabResult, orderId);
			} else {
				for (LabResult labResult : existing) {
					if (overWriteAll) {
						transientLabResult.overwriteExisting(labResult);
						continue;
					}
					
					ImportUiHandler.OverwriteState retVal =
						uiHandler.askOverwrite(transientLabResult.patient, labResult,
							transientLabResult);
					
					if (retVal == ImportUiHandler.OverwriteState.OVERWRITE) {
						transientLabResult.overwriteExisting(labResult);
						continue;
					} else if (retVal == ImportUiHandler.OverwriteState.OVERWRITEALL) {
						overWriteAll = true;
						transientLabResult.overwriteExisting(labResult);
						continue;
					}
				}
			}
		}
		ElexisEventDispatcher.getInstance().fire(
			new ElexisEvent(null, LabResult.class, ElexisEvent.EVENT_RELOAD));
	}
	
	/**
	 * Create the LabResult from the TransientLabResult. Also lookup if there is a matching
	 * LabOrder. If it is in State.ORDERED the created LabResult is set to that LabOrder, else
	 * create a new LabOrder and add the LabResult to it.
	 * 
	 * @param transientLabResult
	 * @param orderId
	 */
	private static void createLabResult(TransientLabResult transientLabResult, String orderId){
		LabResult labResult = transientLabResult.persist();
		
		List<LabOrder> existing =
			LabOrder.getLabOrders(transientLabResult.getPatient(), null,
				transientLabResult.getLabItem(), null, null, null, State.ORDERED);
		
		LabOrder labOrder = null;
		if (existing == null || existing.isEmpty()) {
			TimeTool importTime = transientLabResult.getTransmissionTime();
			if (importTime == null) {
				importTime = transientLabResult.getDate();
				if (importTime == null) {
					importTime = new TimeTool();
				}
			}
			labOrder =
				new LabOrder(CoreHub.actUser, CoreHub.actMandant, transientLabResult.getPatient(),
					transientLabResult.getLabItem(), labResult, orderId, "Import", importTime);
		} else {
			labOrder = existing.get(0);
			labOrder.setLabResult(labResult);
		}
		
		labOrder.setState(State.DONE_IMPORT);
	}
	
	/**
	 * Overwrite this class with actual UI handling in UI plugins.
	 * 
	 * @author thomashu
	 */
	public static abstract class ImportUiHandler {
		public enum OverwriteState {
			OVERWRITE, OVERWRITEALL, IGNORE
		}
		
		protected abstract OverwriteState askOverwrite(Patient patient, LabResult oldResult,
			TransientLabResult newResult);
	}
	
	/**
	 * Used to transport LabResults information to the common import method
	 * {@link LabImportUtil#importLabResults(List, ImportUiHandler)}.
	 * 
	 * @author thomashu
	 */
	public static class TransientLabResult {
		private Patient patient;
		private LabItem labItem;
		private Kontakt origin;
		
		private String result;
		private String comment;
		private String refMale;
		private String refFemale;
		private String unit;
		private int flags;
		
		private TimeTool date;
		private TimeTool analyseTime;
		private TimeTool observationTime;
		private TimeTool transmissionTime;
		
		private TransientLabResult(Builder builder){
			this.patient = builder.patient;
			this.labItem = builder.labItem;
			this.origin = builder.origin;
			
			this.result = builder.result;
			this.comment = builder.comment;
			this.refMale = builder.refMale;
			this.refFemale = builder.refFemale;
			this.unit = builder.unit;
			this.flags = builder.flags;
			
			this.date = builder.date;
			this.analyseTime = builder.analyseTime;
			this.observationTime = builder.observationTime;
			this.transmissionTime = builder.transmissionTime;
		}
		
		private void overwriteExisting(LabResult labResult){
			labResult.set(LabResult.COMMENT, comment);
			labResult.set(LabResult.RESULT, result);
			setFields(labResult);
		}
		
		private LabResult persist(){
			LabResult labResult = new LabResult(patient, date, labItem, result, comment, origin);
			setFields(labResult);
			return labResult;
		}
		
		public String getLabel(){
			StringBuilder sb = new StringBuilder();
			sb.append(labItem.getLabel()).append(", ")
				.append(getDate().toString(TimeTool.DATE_GER)).append(": ").append(getResult());
			return sb.toString();
		}
		
		@Override
		public String toString(){
			StringBuilder sb = new StringBuilder();
			sb.append(labItem.getLabel()).append(", date ")
				.append(getDate().toString(TimeTool.TIMESTAMP));
			
			if (refMale != null) {
				sb.append(" refm ").append(refMale);
			}
			if (refFemale != null) {
				sb.append(" reff ").append(refFemale);
			}
			if (unit != null) {
				sb.append(" unit ").append(unit);
			}
			if (analyseTime != null) {
				sb.append(" aTime ").append(analyseTime.toString(TimeTool.TIMESTAMP));
			}
			if (observationTime != null) {
				sb.append(" oTime ").append(observationTime.toString(TimeTool.TIMESTAMP));
			}
			if (transmissionTime != null) {
				sb.append(" tTime ").append(transmissionTime.toString(TimeTool.TIMESTAMP));
			}
			
			sb.append(" res ").append(getResult());
			return sb.toString();
		}
		
		private void setFields(LabResult labResult){
			if (refMale != null) {
				labResult.setRefMale(refMale);
			}
			if (refFemale != null) {
				labResult.setRefFemale(refFemale);
			}
			if (unit != null) {
				labResult.setUnit(unit);
			}
			if (analyseTime != null) {
				labResult.setAnalyseTime(analyseTime);
			}
			if (observationTime != null) {
				labResult.setObservationTime(observationTime);
			}
			if (transmissionTime != null) {
				labResult.setTransmissionTime(transmissionTime);
			}
			// set all flags at once, flags is a string in the database
			labResult.set(LabResult.FLAGS, Integer.toString(flags));
		}
		
		public Patient getPatient(){
			return patient;
		}
		
		public LabItem getLabItem(){
			return labItem;
		}
		
		public String getResult(){
			return result;
		}
		
		public String getComment(){
			return comment;
		}
		
		public String getRefMale(){
			return refMale;
		}
		
		public String getRefFemale(){
			return refFemale;
		}
		
		public String getUnit(){
			return unit;
		}
		
		public int getFlags(){
			return flags;
		}
		
		public TimeTool getDate(){
			return date;
		}
		
		public TimeTool getAnalyseTime(){
			return analyseTime;
		}
		
		public TimeTool getObservationTime(){
			return observationTime;
		}
		
		public TimeTool getTransmissionTime(){
			return transmissionTime;
		}
		
		public static class Builder {
			// required parameters
			private Patient patient;
			private LabItem labItem;
			private Kontakt origin;
			private String result;
			
			// optional parameters
			private String comment;
			private String refMale;
			private String refFemale;
			private String unit;
			private int flags;
			
			private TimeTool date;
			private TimeTool analyseTime;
			private TimeTool observationTime;
			private TimeTool transmissionTime;
			
			public Builder(Patient patient, Kontakt origin, LabItem labItem, String result){
				this.patient = patient;
				this.labItem = labItem;
				this.result = result;
				this.origin = origin;
			}
			
			public Builder comment(String comment){
				this.comment = comment;
				return this;
			}
			
			public Builder refMale(String refMale){
				this.refMale = refMale;
				return this;
			}
			
			public Builder refFemale(String refFemale){
				this.refFemale = refFemale;
				return this;
			}
			
			public Builder ref(String ref){
				if (patient.getGeschlecht().equals(Patient.MALE)) {
					refMale(ref);
				} else {
					refFemale(ref);
				}
				return this;
			}
			
			public Builder unit(String unit){
				this.unit = unit;
				return this;
			}
			
			public Builder flags(int flags){
				this.flags = flags;
				return this;
			}
			
			public Builder date(TimeTool date){
				this.date = date;
				return this;
			}
			
			public Builder analyseTime(TimeTool analyseTime){
				this.analyseTime = analyseTime;
				return this;
			}
			
			public Builder observationTime(TimeTool observationTime){
				this.observationTime = observationTime;
				return this;
			}
			
			public Builder transmissionTime(TimeTool transmissionTime){
				this.transmissionTime = transmissionTime;
				return this;
			}
			
			public TransientLabResult build(){
				return new TransientLabResult(this);
			}
		}
	}
}
