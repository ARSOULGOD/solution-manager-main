import { Box, Typography } from '@mui/material';
import { useState, type ChangeEvent, type ReactElement, type ReactNode, type UIEvent } from 'react';

import { t } from '@shared/i18n';
import { Alert, Button } from '@shared/ui';

export interface YamlEditorProps {
  value: string;
  error?: string | undefined;
  omittedSecrets?: number | undefined;
  disabled?: boolean | undefined;
  onChange: (value: string) => void;
  onCopy: () => void;
  onPaste: () => void;
}

function highlightedLines(value: string): ReactNode {
  return value
    .split('\n')
    .map((line, index) => {
      const key = `line-${index}`;
      if (line.trimStart().startsWith('#')) {
        return (
          <Box component="span" key={key} sx={{ color: 'text.secondary' }}>
            {line}
          </Box>
        );
      }
      const match = /^(\s*)([^:#\n]+?)(:)(\s*)(.*)$/.exec(line);
      if (match === null) {
        return <span key={key}>{line}</span>;
      }
      const [, indent, keyText, colon, spacing, valueText] = match;
      return (
        <span key={key}>
          {indent}
          <Box component="span" sx={{ color: 'primary.dark' }}>
            {keyText}
          </Box>
          <Box component="span" sx={{ color: 'text.secondary' }}>
            {colon}
            {spacing}
          </Box>
          <Box component="span" sx={{ color: 'secondary.dark' }}>
            {valueText}
          </Box>
        </span>
      );
    })
    .reduce<ReactNode[]>((nodes, line, index) => {
      if (index > 0) nodes.push('\n');
      nodes.push(line);
      return nodes;
    }, []);
}

export function YamlEditor({
  value,
  error,
  omittedSecrets = 0,
  disabled = false,
  onChange,
  onCopy,
  onPaste,
}: YamlEditorProps): ReactElement {
  const [scrollTop, setScrollTop] = useState(0);
  const [scrollLeft, setScrollLeft] = useState(0);

  const handleChange = (event: ChangeEvent<HTMLTextAreaElement>) => onChange(event.target.value);

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.25 }}>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 1,
          flexWrap: 'wrap',
        }}
      >
        <Box>
          <Typography variant="h6">{t('designerYamlTitle')}</Typography>
          <Typography variant="body2" color="text.secondary">
            {t('designerYamlDescription')}
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', gap: 1 }}>
          <Button variant="outlined" size="sm" onClick={onCopy} disabled={disabled}>
            {t('designerYamlCopy')}
          </Button>
          <Button variant="outlined" size="sm" onClick={onPaste} disabled={disabled}>
            {t('designerYamlPaste')}
          </Button>
        </Box>
      </Box>

      {omittedSecrets > 0 ? (
        <Alert severity="info" title={t('designerYamlSecretsTitle')} fullWidth>
          {t('designerYamlSecretsDetail', { count: omittedSecrets })}
        </Alert>
      ) : null}
      {error !== undefined ? (
        <Alert severity="err" title={t('designerYamlInvalidTitle')} fullWidth>
          {error}
        </Alert>
      ) : null}

      <Box
        sx={{
          position: 'relative',
          minHeight: 480,
          border: 1,
          borderColor: error === undefined ? 'divider' : 'error.main',
          borderRadius: 1,
          overflow: 'hidden',
          backgroundColor: 'background.default',
        }}
      >
        <Box
          component="pre"
          aria-hidden="true"
          sx={{
            position: 'absolute',
            inset: 0,
            m: 0,
            p: 2,
            overflow: 'hidden',
            transform: `translate(${-scrollLeft}px, ${-scrollTop}px)`,
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
            fontSize: 13,
            lineHeight: 1.6,
            whiteSpace: 'pre',
            pointerEvents: 'none',
          }}
        >
          {highlightedLines(value)}
        </Box>
        <Box
          component="textarea"
          value={value}
          onChange={handleChange}
          onScroll={(event: UIEvent<HTMLTextAreaElement>) => {
            setScrollTop(event.currentTarget.scrollTop);
            setScrollLeft(event.currentTarget.scrollLeft);
          }}
          disabled={disabled}
          aria-label={t('designerYamlAriaLabel')}
          spellCheck={false}
          sx={{
            position: 'relative',
            zIndex: 1,
            display: 'block',
            width: '100%',
            height: '100%',
            minHeight: 480,
            resize: 'vertical',
            border: 0,
            outline: 0,
            p: 2,
            background: 'transparent',
            color: 'transparent',
            caretColor: 'text.primary',
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
            fontSize: 13,
            lineHeight: 1.6,
            whiteSpace: 'pre',
            overflow: 'auto',
          }}
        />
      </Box>
    </Box>
  );
}
