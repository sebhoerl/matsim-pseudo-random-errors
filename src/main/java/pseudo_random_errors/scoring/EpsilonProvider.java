package pseudo_random_errors.scoring;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

public interface EpsilonProvider {
	double getEpsilon(Id<Person> personId, int tripIndex, Object alternative);
}
