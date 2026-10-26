package de.smartdata.jobs.actions;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import java.util.ArrayList;
import java.util.List;

/**
 * Class for archiving a automatic generated Action documentation.
 * 
 * @author Florian Fehring
 */
public class ActionDocumentation {
    
    public JsonArray getDocumentation() {
        JsonArrayBuilder jab = Json.createArrayBuilder();
        
        // Get Action classes
        //TODO use Reflection to avoid manual adding of classes there
        List<Action> actions = new ArrayList<>();
        actions.add(new CheckNetAction());
        actions.add(new CommandExecutionAction());
        actions.add(new MailAction());
        
        // Create documentation
        for(Action curAction : actions) {
            JsonObjectBuilder job = Json.createObjectBuilder();
            job.add("name", curAction.getClass().getSimpleName());
            job.add("params", curAction.getParamsDescription());
            jab.add(job);
        }
        
        return jab.build();
    }
}
