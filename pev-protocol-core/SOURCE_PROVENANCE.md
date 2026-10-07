# Source provenance

Rule: unknown license => do not copy. Protocol *facts* (offsets, units, commands) are re-specified
neutrally and implemented independently; no GPL class, test, or expressive spec text is
copied, translated or refactored into this MIT module. Having read a source is not a clean-room claim.

| Source | URL / ref | License | Used for | Redistributable |
|---|---|---|---|---|
| M365-Rokid-HUD (this repo) | github.com/zero2005x/M365-Rokid-HUD @ 3d01e6f | MIT | code, fixtures | yes |
| PEVAppRE findings (local, read-only) | `/home/kali/PEVAppRE` (euc-programme/findings, scooter-apps/) | owner's own research notes; vendor-app derived facts | protocol facts only; no vendor code | notes: owner decision; **vendor code/captures: no** |
| RideFlux (local) | `Android/RideFlux` @ 6f3ad34 (+uncommitted edits) | GPL | NOT a code source. Consumer of this core only | n/a |
| Vendor apps (Begode/Gotway, KingSong, Inmotion, Veteran, Zydtech, Ninebot) | via PEVAppRE | proprietary | facts about wire format only | no |

## Per-fixture log (append rows)
| Fixture | Origin | Evidence label | Transform / hash | Redistributable |
|---|---|---|---|---|
| (none yet) | | | | |

Open gaps: confirm redistribution rights for any wire capture before committing it as a fixture;
derive fixtures into `src/test/resources/fixtures/` with source sha256 + transform tool hash recorded above.
