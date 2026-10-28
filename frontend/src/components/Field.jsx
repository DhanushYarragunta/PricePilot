import { useId } from 'react';

/**
 * A labelled input with its hint and its error.
 *
 * The point of the component is the wiring, not the markup: the label is bound to the input by id,
 * `aria-describedby` points at whichever of the hint and the error is present, and `aria-invalid`
 * is set when there is an error. Doing that by hand at each of the nine inputs in this app would
 * mean getting it right nine times, and the usual outcome is a form where the label is decorative
 * and a screen reader reads an unlabelled box.
 *
 * `useId` rather than a passed-in name, so two instances of the same form on one page — the sign-in
 * and create-account forms share a component — cannot produce duplicate ids.
 */
export default function Field({
  label,
  hint,
  error,
  type = 'text',
  as = 'input',
  value,
  onChange,
  ...rest
}) {
  const id = useId();
  const hintId = id + '-hint';
  const errorId = id + '-error';
  const described = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ');

  const common = {
    id,
    value: value ?? '',
    onChange: (event) => onChange(event.target.value),
    'aria-invalid': error ? 'true' : undefined,
    'aria-describedby': described || undefined,
    ...rest,
  };

  return (
    <div className="field">
      <label className="field-label" htmlFor={id}>
        {label}
      </label>
      {as === 'textarea' ? (
        <textarea className="textarea" {...common} />
      ) : (
        <input className="input" type={type} {...common} />
      )}
      {/*
       * The hint stays visible when there is an error. Replacing it with the error — the common
       * pattern — removes the instruction at the exact moment the person needs it most, since the
       * usual reason for an error is that they did not follow the instruction.
       */}
      {hint ? (
        <div className="field-hint" id={hintId}>
          {hint}
        </div>
      ) : null}
      {error ? (
        <div className="field-error" id={errorId}>
          {error}
        </div>
      ) : null}
    </div>
  );
}

/** A checkbox with its label to the right, where the whole row is the hit area. */
export function CheckField({ label, hint, checked, onChange, ...rest }) {
  return (
    <label className="checkbox">
      <input
        type="checkbox"
        checked={Boolean(checked)}
        onChange={(event) => onChange(event.target.checked)}
        {...rest}
      />
      <span>
        <span>{label}</span>
        {hint ? <div className="field-hint">{hint}</div> : null}
      </span>
    </label>
  );
}
