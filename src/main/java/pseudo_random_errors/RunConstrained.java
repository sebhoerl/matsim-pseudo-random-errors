package pseudo_random_errors;

import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.GlobalConfigGroup;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup.ActivityParams;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup.ModeParams;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup.ScoringParameterSet;
import org.matsim.core.config.groups.PlansCalcRouteConfigGroup.ModeRoutingParams;
import org.matsim.core.config.groups.QSimConfigGroup.TrafficDynamics;
import org.matsim.core.config.groups.StrategyConfigGroup;
import org.matsim.core.config.groups.StrategyConfigGroup.StrategySettings;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.scoring.functions.CharyparNagelScoringFunctionFactory;

import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import pseudo_random_errors.replanning.RandomModeModule;
import pseudo_random_errors.scoring.EpsilonModeScoring;
import pseudo_random_errors.scoring.EpsilonProvider;
import pseudo_random_errors.scoring.GumbelEpsilonProvider;

public class RunConstrained {
	static public void main(String[] args) throws ConfigurationException {
		// Other concerns:
		// a) Same score leads to one mode chosen (because of max selection and plan
		// removal)
		// b) Free speed network simulation vs. freespeed factor 1.0 = QSim will round
		// up
		// c) Free speed factor routes are longer than network routed routes!
		// d) Car travel time somethimes 00:01:41 and sometimes 00:01:40 in route in
		// plans

		CommandLine cmd = new CommandLine.Builder(args) //
				.allowOptions("selection-strategy", "innovation-rate", "innovation-strategy") //
				.allowOptions("car-score", "pt-score") //
				.allowOptions("use-epsilons", "population-size") //
				.allowOptions("capacity") //
				.build();

		// CONFIG PART
		Config config = ConfigUtils.createConfig();

		// Some initial setup
		config.controler().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);
		config.controler().setLastIteration(300);
		config.controler().setOutputDirectory("simulation_output");

		// Setting up the activity scoring parameters ...
		ScoringParameterSet scoringParameters = config.planCalcScore().getOrCreateScoringParameters(null);

		// ... for generic activity
		ActivityParams homeParams = scoringParameters.getOrCreateActivityParams("generic");
		homeParams.setTypicalDuration(1.0);
		homeParams.setScoringThisActivityAtAll(false);

		// ... for car
		ModeParams carParams = scoringParameters.getOrCreateModeParams("car");
		carParams.setConstant(0.0);
		carParams.setMarginalUtilityOfTraveling(cmd.getOption("car-score").map(Double::parseDouble).orElse(-0.1));
		carParams.setMarginalUtilityOfDistance(0.0);
		carParams.setMonetaryDistanceRate(0.0);

		// ... for pt
		ModeParams ptParams = scoringParameters.getOrCreateModeParams("pt");
		ptParams.setConstant(0.0);
		ptParams.setMarginalUtilityOfTraveling(cmd.getOption("pt-score").map(Double::parseDouble).orElse(-0.2));
		ptParams.setMarginalUtilityOfDistance(0.0);
		ptParams.setMonetaryDistanceRate(0.0);

		// Setting up replanning ...
		StrategyConfigGroup strategyConfig = config.strategy();
		strategyConfig.clearStrategySettings();
		strategyConfig.setMaxAgentPlanMemorySize(3);

		double innovationRate = cmd.getOption("innovation-rate").map(Double::parseDouble).orElse(0.1);

		// ... for selection
		StrategySettings selectionStrategy = new StrategySettings();
		selectionStrategy.setStrategyName(cmd.getOption("selection-strategy").orElse("ChangeExpBeta"));
		selectionStrategy.setWeight(1.0 - innovationRate);
		strategyConfig.addStrategySettings(selectionStrategy);

		// ... for innovation
		StrategySettings innovationStrategy = new StrategySettings();
		innovationStrategy.setStrategyName(cmd.getOption("innovation-strategy").orElse("RandomMode"));
		innovationStrategy.setWeight(innovationRate);
		strategyConfig.addStrategySettings(innovationStrategy);

		config.changeMode().setModes(new String[] { "car", "pt" });

		// Setting up routing ...
		config.plansCalcRoute().setNetworkModes(Collections.singleton("car"));
		config.plansCalcRoute().setRoutingRandomness(0.0);
		config.qsim().setMainModes(Collections.singleton("car"));
		config.qsim().setTrafficDynamics(TrafficDynamics.queue);
		config.qsim().setStorageCapFactor(1e6);
		config.travelTimeCalculator().setAnalyzedModes(Collections.singleton("car"));
		config.travelTimeCalculator().setTraveltimeBinSize(10 * 3600);
		config.linkStats().setWriteLinkStatsInterval(0);

		// ... for car ...
		// ModeRoutingParams carRoutingParams =
		// config.plansCalcRoute().getOrCreateModeRoutingParams("car");
		// carRoutingParams.setTeleportedModeFreespeedFactor(1.0);

		// ... and for public transport
		ModeRoutingParams ptRoutingParams = config.plansCalcRoute().getOrCreateModeRoutingParams("pt");
		ptRoutingParams.setTeleportedModeFreespeedFactor(null);
		ptRoutingParams.setTeleportedModeSpeed(2000.0 / 103.00001);
		ptRoutingParams.setBeelineDistanceFactor(1.0);

		// Set configuration options
		cmd.applyConfiguration(config);

		Scenario scenario = ScenarioUtils.createScenario(config);

		// NETWORK PART
		Network network = scenario.getNetwork();
		NetworkFactory networkFactory = network.getFactory();

		/*-
		 *  N1 ------ N2 ------ N3 ------ N4
		 *       L1        L2        L3
		 */

		Node node1 = networkFactory.createNode(Id.createNodeId("N1"), new Coord(0.0, 1000.0));
		Node node2 = networkFactory.createNode(Id.createNodeId("N2"), new Coord(0.0, 2000.0));
		Node node3 = networkFactory.createNode(Id.createNodeId("N3"), new Coord(0.0, 3000.0));
		Node node4 = networkFactory.createNode(Id.createNodeId("N4"), new Coord(0.0, 3000.0));

		Link link1 = networkFactory.createLink(Id.createLinkId("L1"), node1, node2);
		Link link2 = networkFactory.createLink(Id.createLinkId("L2"), node2, node3);
		Link link3 = networkFactory.createLink(Id.createLinkId("L3"), node3, node4);

		network.addNode(node1);
		network.addNode(node2);
		network.addNode(node3);
		network.addNode(node4);

		network.addLink(link1);
		network.addLink(link2);
		network.addLink(link3);

		for (Link link : Arrays.asList(link1, link2, link3)) {
			link.setFreespeed(10.0);
			link.setCapacity(cmd.getOption("capacity").map(Integer::parseInt).orElse(700000));
		}

		// POPULATION PART
		Population population = scenario.getPopulation();
		PopulationFactory factory = population.getFactory();

		Random random = new Random(config.global().getRandomSeed());

		int populationSize = cmd.getOption("population-size").map(Integer::parseInt).orElse(10000);
		for (int k = 0; k < populationSize; k++) {
			Person person = factory.createPerson(Id.createPersonId(k));
			population.addPerson(person);

			Plan plan = factory.createPlan();
			person.addPlan(plan);

			Activity startActivity = factory.createActivityFromLinkId("generic", Id.createLinkId("L1"));
			startActivity.setEndTime(0.0);
			startActivity.setCoord(node1.getCoord());
			plan.addActivity(startActivity);

			Leg leg = factory.createLeg(random.nextBoolean() ? "car" : "pt");
			plan.addLeg(leg);

			Activity endActivity = factory.createActivityFromLinkId("generic", Id.createLinkId("L3"));
			endActivity.setCoord(node4.getCoord());
			plan.addActivity(endActivity);
		}

		// CONTROLLER PART
		Controler controller = new Controler(scenario);
		controller.addOverridingModule(new RandomModeModule());

		if (cmd.getOption("use-epsilons").map(Boolean::parseBoolean).orElse(false)) {
			controller.addOverridingModule(new AbstractModule() {
				@Override
				public void install() {
					bind(CharyparNagelScoringFunctionFactory.class);
					bind(EpsilonProvider.class).to(GumbelEpsilonProvider.class);
				}

				@Provides
				@Singleton
				ScoringFunctionFactory provideScoringFunctionFactory(CharyparNagelScoringFunctionFactory delegate,
						Provider<EpsilonProvider> epsilonProvider) {
					return new ScoringFunctionFactory() {
						@Override
						public ScoringFunction createNewScoringFunction(Person person) {
							SumScoringFunction scoringFunction = (SumScoringFunction) delegate
									.createNewScoringFunction(person);
							scoringFunction
									.addScoringFunction(new EpsilonModeScoring(person.getId(), epsilonProvider.get()));
							return scoringFunction;
						}
					};
				}

				@Provides
				public GumbelEpsilonProvider provideGumbelEpsilonProvider(GlobalConfigGroup config) {
					return new GumbelEpsilonProvider(config.getRandomSeed(), 1.0);
				}
			});

		}

		controller.run();
	}
}
