# Biķernieku Race Track Calendar: deployment branch

This branch holds only what the Raspberry Pi needs:

- `run.sh`: the daily cron job. It installs the latest release binary, regenerates the calendar and pushes it here.
- `bikernieki.ics`: the generated calendar, committed by the Pi every day.

**Subscribe:** `https://ej.uz/bksb_calendar`, or directly
`https://raw.githubusercontent.com/sknarovs/bksb-calendar/main/bikernieki.ics`

On the Pi, clone only this branch:

```bash
git clone --single-branch --branch main git@github.com:sknarovs/bksb-calendar.git ~/bksb-calendar
```

The source code, the build and the full setup guide are on the [`dev`](https://github.com/sknarovs/bksb-calendar/tree/dev) branch.
