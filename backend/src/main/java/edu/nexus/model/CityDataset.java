package edu.nexus.model;

import java.util.List;

/**
 * Complete synthetic city model sent with every analysis request (the API is stateless, so many
 * visitors can use one deployment without interfering with each other).
 */
public record CityDataset(DatasetMeta meta, List<CityNode> nodes, List<Road> roads, List<Incident> incidents,
                          DemandTimeline demandTimeline) {}
