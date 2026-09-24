/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */

import {DataTable, type DataTableColumn} from '@camunda/design-system';
import type {ExpandableListRow, ExpandableListVariantProps} from '../ExpandableList.types';
import './ComposedCell.css';

/**
 * Carbon's row shape: one opaque content cell per row, into which the consumer
 * composes the name, the counts and the ratio bar together.
 */
const ComposedCell: React.FC<ExpandableListVariantProps> = ({header, rows, renderExpansion}) => {
	// DataTable always renders a header row; it's reduced to an sr-only label here
	// since this list has none in Carbon. Recorded for design review, see
	// docs/migration/operate-dashboard-ds-gaps.md.
	const columns: DataTableColumn<ExpandableListRow>[] = [
		{
			id: 'content',
			header: () => <span className="sr-only">{header}</span>,
			// `data-expandable` marks whether this row has anything to expand into,
			// so ComposedCell.css can hide DataTable's own always-on expand toggle
			// for it — matching Carbon's `button { display: none }` on the same case.
			cell: ({row}) => <div data-expandable={renderExpansion(row.original) !== null}>{row.original.content}</div>,
		},
	];

	return (
		<div className="composed-cell-table contents">
			<DataTable<ExpandableListRow>
				size="sm"
				columns={columns}
				data={rows}
				expansion={renderExpansion}
				aria-label={header}
				getRowId={(row) => row.id}
			/>
		</div>
	);
};

export {ComposedCell};
