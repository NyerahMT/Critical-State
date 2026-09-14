# Third-Party Notices

## IF97 — water and steam properties

Critical State currently uses **IF97 2.0.0** by Hummeling Engineering BV through the Maven dependency `com.hummeling:if97:2.0.0`.

- Project: https://github.com/hummeling/if97
- Website: https://www.if97.software/
- License: GNU Lesser General Public License, version 3 or (at the project's option) any later version
- Copyright: 2009–2022 Hummeling Engineering BV

The library is used as an unmodified dependency behind `WaterProperties`, a project-owned interface. Critical State's plant-model code is separate from the IF97 library and does not copy its implementation.

The IF97 library's license text and corresponding source are available from the upstream project. If Critical State moves toward broader commercial distribution, dependency packaging and LGPL compliance should be reviewed before release; the adapter exists so another compatible property backend can be substituted without redesigning the simulation.
