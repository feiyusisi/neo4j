/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.ha.cluster;

/**
 * These callback methods correspond to the cluster
 */
public interface HighAvailabilityMemberListener
{
    void masterIsElected( HighAvailabilityMemberChangeEvent event );

    void masterIsAvailable( HighAvailabilityMemberChangeEvent event );

    void slaveIsAvailable( HighAvailabilityMemberChangeEvent event );

    void instanceStops( HighAvailabilityMemberChangeEvent event );

    public static class Adapter implements HighAvailabilityMemberListener
    {
        @Override
        public void masterIsElected( HighAvailabilityMemberChangeEvent event )
        {
        }

        @Override
        public void masterIsAvailable( HighAvailabilityMemberChangeEvent event )
        {
        }

        @Override
        public void slaveIsAvailable( HighAvailabilityMemberChangeEvent event )
        {
        }

        @Override
        public void instanceStops( HighAvailabilityMemberChangeEvent event )
        {
        }
    }
}
