package ch.elexis.core.ui.laboratory.commands;

import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

import ch.elexis.data.LabItem;
import ch.elexis.data.LabMapping;
import ch.elexis.data.Query;

public class CreateMappingFrom2_1_7 extends AbstractHandler {
	public static final String COMMANDID = "ch.elexis.mapping.2_1_7.create"; //$NON-NLS-1$
	
	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException{
		Query<LabItem> qbli = new Query<LabItem>(LabItem.class);
		List<LabItem> items = qbli.execute();
		
		for (LabItem item : items) {
			LabMapping mapping =
				LabMapping.getByContactAndItemId(item.getLabor().getId(), item.getId());
			if (mapping == null) {
				mapping =
					new LabMapping(item.getLabor().getId(), item.getKuerzel(), item.getId(), false);
			}
		}
		return null;
	}
	
}
